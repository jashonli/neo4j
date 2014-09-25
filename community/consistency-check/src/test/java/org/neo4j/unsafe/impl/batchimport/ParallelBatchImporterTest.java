/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.neo4j.collection.pool.Pool;
import org.neo4j.consistency.ConsistencyCheckService;
import org.neo4j.consistency.ConsistencyCheckService.Result;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.logging.DevNullLoggingService;
import org.neo4j.test.TargetDirectory;
import org.neo4j.tooling.GlobalGraphOperations;
import org.neo4j.unsafe.impl.batchimport.cache.LongArrayFactory;
import org.neo4j.unsafe.impl.batchimport.cache.idmapping.IdMapping;
import org.neo4j.unsafe.impl.batchimport.cache.idmapping.IdMappings;
import org.neo4j.unsafe.impl.batchimport.input.InputNode;
import org.neo4j.unsafe.impl.batchimport.input.InputRelationship;
import org.neo4j.unsafe.impl.batchimport.staging.SilentExecutionMonitor;
import org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.Writer;
import org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.WriterFactory;
import org.neo4j.unsafe.impl.batchimport.store.io.IoQueue;
import org.neo4j.unsafe.impl.batchimport.store.io.Monitor;

import static java.lang.System.currentTimeMillis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static org.neo4j.helpers.collection.IteratorUtil.count;
import static org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.SYNCHRONOUS;

@RunWith(Parameterized.class)
public class ParallelBatchImporterTest
{
    private static final String[] LABELS = new String[]{"Person", "Guy"};
    private static final int NODE_COUNT = 10_000;
    public final @Rule TargetDirectory.TestDirectory directory = TargetDirectory.testDirForTest( getClass() );
    private static final Configuration config = new Configuration.Default()
    {
        @Override
        public int batchSize()
        {
            // Set to extra low to exercise the internals and IoQueue a bit more.
            return 100;
        }

        @Override
        public int denseNodeThreshold()
        {
            return 30;
        }
    };
    private final WriterFactory writerFactory;
    private final InputIdGenerator idGenerator;
    private final IdMapping idMapping;

    @Parameterized.Parameters(name = "{0},{1},{2}")
    public static Collection<Object[]> data()
    {
        return Arrays.asList(
                // synchronous I/O, actual node id input
                new Object[]{SYNCHRONOUS, new LongInputIdGenerator(), IdMappings.actual()},
                // extra slow synchronous I/O, actual node id input
                new Object[]{synchronousSlowWriterFactory, new LongInputIdGenerator(), IdMappings.actual()},
                // synchronous I/O, string id input
                new Object[]{SYNCHRONOUS, new StringInputIdGenerator(), IdMappings.strings( LongArrayFactory.AUTO )},

                // FIXME: we've seen this fail before with inconsistencies due to some kind of race in IoQueue
                //        enabled here to try and trigger the error so that we can fix it.
                // extra slow parallel I/O, actual node id input
                new Object[]{new IoQueue( new Random().nextInt( 2 )+2, synchronousSlowWriterFactory ),
                        new LongInputIdGenerator(), IdMappings.actual()}
        );
    }

    public ParallelBatchImporterTest( WriterFactory writerFactory, InputIdGenerator idGenerator, IdMapping idMapping )
    {
        this.writerFactory = writerFactory;
        this.idGenerator = idGenerator;
        this.idMapping = idMapping;

    }

    @Test
    public void shouldImportCsvData() throws Exception
    {
        // GIVEN
        final BatchImporter inserter = new ParallelBatchImporter( directory.absolutePath(),
                new DefaultFileSystemAbstraction(), config, new DevNullLoggingService(),
                new SilentExecutionMonitor(), writerFactory );

        // WHEN
        inserter.doImport(
                nodes( NODE_COUNT, idGenerator ),
                relationships( NODE_COUNT * 10, idGenerator ),
                idMapping );

        // THEN
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( directory.absolutePath() );
        try ( Transaction tx = db.beginTx() )
        {
            verifyData( NODE_COUNT, db );
            tx.success();
        }
        finally
        {
            db.shutdown();
        }
        assertConsistent( directory.absolutePath() );
    }

    private void assertConsistent( String storeDir ) throws ConsistencyCheckIncompleteException
    {
        ConsistencyCheckService consistencyChecker = new ConsistencyCheckService();
        Result result = consistencyChecker.runFullConsistencyCheck( storeDir,
                new Config(), ProgressMonitorFactory.NONE, StringLogger.DEV_NULL );
        assertTrue( "Database contains inconsistencies, there should be a report in " + storeDir,
                result.isSuccessful() );
    }

    private static abstract class InputIdGenerator
    {
        protected final long seed = currentTimeMillis();
        protected volatile Random randomType, random;

        abstract Object nextNodeId();

        void resetRandomness()
        {
            randomType = new Random( seed );
            random = new Random( seed );
        }

        abstract Object randomExisting();

        String randomType()
        {
            return "TYPE" + randomType.nextInt( 3 );
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName();
        }
    }

    private static class LongInputIdGenerator extends InputIdGenerator
    {
        private volatile int id;

        @Override
        Object nextNodeId()
        {
            return (long) id++;
        }

        @Override
        Object randomExisting()
        {
            return (long) random.nextInt( NODE_COUNT );
        }
    }

    private static class StringInputIdGenerator extends InputIdGenerator
    {
        private final List<String> strings = new ArrayList<>();

        @Override
        Object nextNodeId()
        {
            String string = UUID.randomUUID().toString();
            strings.add( string );
            return string;
        }

        @Override
        Object randomExisting()
        {
            return strings.get( random.nextInt( strings.size() ) );
        }
    }

    protected void verifyData( int nodeCount, GraphDatabaseService db )
    {
        // Verify that all the labels are in place
        Set<Label> expectedLabels = new HashSet<>();
        for ( String label : LABELS )
        {
            expectedLabels.add( DynamicLabel.label( label ) );
        }
        GlobalGraphOperations globalOps = GlobalGraphOperations.at( db );
        Set<Label> allLabels = Iterables.toSet( globalOps.getAllLabels() );
        assertEquals( allLabels, expectedLabels );

        // Sample some nodes for deeper inspection of their contents
        Random random = new Random();
        for ( int i = 0; i < nodeCount / 10; i++ )
        {
            Node node = db.getNodeById( random.nextInt( nodeCount ) );
            int count = count( node.getRelationships() );
            assertEquals( "For node " + node, count, node.getDegree() );
            for ( String key : node.getPropertyKeys() )
            {
                node.getProperty( key );
                Set<Label> actualLabels = Iterables.toSet( node.getLabels() );
                assertEquals( actualLabels, expectedLabels );
            }
        }

        // The label scan store should find all nodes since they all have the same labels
        Label firstLabel = DynamicLabel.label( LABELS[0] );
        ResourceIterable<Node> allNodesWithLabel =
                globalOps.getAllNodesWithLabel( firstLabel );
        assertEquals( count( allNodesWithLabel ), nodeCount );

        // All nodes also have the same age=10 property, so we should again find them all
        ResourceIterable<Node> foundNodes =
                db.findNodesByLabelAndProperty( firstLabel, "age", 10 );
        assertEquals( count( foundNodes ), nodeCount );
    }

    private static WriterFactory synchronousSlowWriterFactory = new WriterFactory()
    {
        @Override
        public Writer create( final StoreChannel channel, final Monitor monitor )
        {
            return new Writer()
            {
                final Writer delegate = SYNCHRONOUS.create( channel, monitor );
                final Random random = new Random();

                @Override
                public void write( ByteBuffer data, long position, Pool<ByteBuffer> pool )
                        throws IOException
                {
                    if ( random.nextInt( 10 ) == 0 )
                    {
                        LockSupport.parkNanos( random.nextInt( 100 ) * 1_000_000 ); // slowness comes from here
                    }
                    delegate.write( data, position, pool );

                }
            };
        }

        @Override
        public void awaitEverythingWritten()
        { //noop
        }

        @Override
        public void shutdown()
        { //noop
        }

        @Override
        public String toString()
        {
            return "Randomly slow";
        }
    };

    private static Iterable<InputRelationship> relationships( final long count, final InputIdGenerator idGenerator )
    {
        return new Iterable<InputRelationship>()
        {
            @Override
            public Iterator<InputRelationship> iterator()
            {
                idGenerator.resetRandomness();
                return new PrefetchingIterator<InputRelationship>()
                {
                    private int cursor;

                    @Override
                    protected InputRelationship fetchNextOrNull()
                    {
                        if ( cursor < count )
                        {
                            Object[] properties = new Object[]{
                                    "name", "Nisse " + cursor,
                                    "age", 10,
                                    "long-string", "OK here goes... a long string that will certainly end up in a dynamic " +
                                    "record1234567890!@#$%^&*()_|",
                                    "array", new long[]{1234567890123L, 987654321987L, 123456789123L, 987654321987L}
                            };

                            try
                            {
                                Object startNode = idGenerator.randomExisting();
                                Object endNode = idGenerator.randomExisting();
                                return new InputRelationship( cursor, properties, null,
                                        startNode, endNode,
                                        idGenerator.randomType(), null );
                            }
                            finally
                            {
                                cursor++;
                            }
                        }
                        return null;
                    }
                };
            }
        };
    }

    private static Iterable<InputNode> nodes( final long count, final InputIdGenerator idGenerator )
    {
        return new Iterable<InputNode>()
        {
            @Override
            public Iterator<InputNode> iterator()
            {
                return new PrefetchingIterator<InputNode>()
                {
                    private int cursor;

                    @Override
                    protected InputNode fetchNextOrNull()
                    {
                        if ( cursor < count )
                        {
                            Object[] properties = new Object[]{
                                    "name", "Nisse " + cursor,
                                    "age", 10,
                                    "long-string", "OK here goes... a long string that will certainly end up in a dynamic " +
                                    "record1234567890!@#$%^&*()_|",
                                    "array", new long[]{1234567890123L, 987654321987L, 123456789123L, 987654321987L}
                            };

                            try
                            {
                                return new InputNode( idGenerator.nextNodeId(), properties, null, LABELS, null );
                            }
                            finally
                            {
                                cursor++;
                            }
                        }
                        return null;
                    }
                };
            }
        };
    }
}
