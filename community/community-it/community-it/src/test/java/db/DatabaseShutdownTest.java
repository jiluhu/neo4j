/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;

import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.event.DatabaseEventHandlerAdapter;
import org.neo4j.graphdb.facade.ExternalDependencies;
import org.neo4j.graphdb.facade.GraphDatabaseFacadeFactory;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.edition.CommunityEditionModule;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.DelegatingPageCache;
import org.neo4j.io.pagecache.DelegatingPagedFile;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.kernel.database.DatabaseId;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.LifecycleStatus;
import org.neo4j.kernel.monitoring.tracing.Tracers;
import org.neo4j.logging.internal.LogService;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.extension.EphemeralFileSystemExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith( {EphemeralFileSystemExtension.class, TestDirectoryExtension.class, } )
class DatabaseShutdownTest
{
    @Inject
    private TestDirectory testDirectory;

    @Test
    void shouldShutdownCorrectlyWhenCheckPointingOnShutdownFails()
    {
        TestGraphDatabaseFactoryWithFailingPageCacheFlush factory = new TestGraphDatabaseFactoryWithFailingPageCacheFlush();
        DatabaseLayout databaseLayout = testDirectory.databaseLayout();
        GraphDatabaseService databaseService = factory.newEmbeddedDatabase( databaseLayout.databaseDirectory() );
        DatabaseManager<?> databaseManager = ((GraphDatabaseAPI) databaseService).getDependencyResolver().resolveDependency( DatabaseManager.class );
        var databaseContext = databaseManager.getDatabaseContext( new DatabaseId( databaseLayout.getDatabaseName() ) );
        factory.setFailFlush( true );
        databaseService.shutdown();
        DatabaseContext context = databaseContext.get();
        assertTrue( context.isFailed() );
        assertEquals( LifecycleStatus.SHUTDOWN, factory.getDatabaseStatus() );
    }

    @Test
    void invokeKernelEventHandlersBeforeShutdown()
    {
        GraphDatabaseService database = new TestGraphDatabaseFactory().newEmbeddedDatabase( testDirectory.databaseDir() );
        ShutdownListenerDatabaseEventHandler shutdownHandler = new ShutdownListenerDatabaseEventHandler();
        database.registerDatabaseEventHandler( shutdownHandler );
        database.shutdown();

        assertTrue( shutdownHandler.isShutdownInvoked() );
    }

    private static class TestGraphDatabaseFactoryWithFailingPageCacheFlush extends TestGraphDatabaseFactory
    {
        private LifeSupport globalLife;
        private volatile boolean failFlush;

        @Override
        protected DatabaseManagementService newEmbeddedDatabase( File storeDir, Config config,
                ExternalDependencies dependencies )
        {
            return new GraphDatabaseFacadeFactory( DatabaseInfo.COMMUNITY, CommunityEditionModule::new )
            {

                @Override
                protected GlobalModule createGlobalModule( File storeDir, Config config, ExternalDependencies dependencies )
                {
                    GlobalModule globalModule = new GlobalModule( storeDir, config, databaseInfo, dependencies )
                    {
                        @Override
                        protected PageCache createPageCache( FileSystemAbstraction fileSystem, Config config, LogService logging, Tracers tracers,
                                VersionContextSupplier versionContextSupplier, JobScheduler jobScheduler )
                        {
                            PageCache pageCache = super.createPageCache( fileSystem, config, logging, tracers, versionContextSupplier, jobScheduler );
                            return new DelegatingPageCache( pageCache )
                            {
                                @Override
                                public PagedFile map( File file, int pageSize, OpenOption... openOptions ) throws IOException
                                {
                                    PagedFile pagedFile = super.map( file, pageSize, openOptions );
                                    return new DelegatingPagedFile( pagedFile )
                                    {
                                        @Override
                                        public void flushAndForce( IOLimiter limiter ) throws IOException
                                        {
                                            if ( failFlush )
                                            {
                                                // this is simulating a failing check pointing on shutdown
                                                throw new IOException( "Boom!" );
                                            }
                                            super.flushAndForce( limiter );
                                        }
                                    };
                                }
                            };
                        }
                    };
                    globalLife = globalModule.getGlobalLife();
                    return globalModule;
                }
            }.newFacade( storeDir, config, dependencies );
        }

        LifecycleStatus getDatabaseStatus()
        {
            return globalLife.getStatus();
        }

        void setFailFlush( boolean failFlush )
        {
            this.failFlush = failFlush;
        }
    }

    private static class ShutdownListenerDatabaseEventHandler extends DatabaseEventHandlerAdapter
    {
        private volatile boolean shutdownInvoked;

        @Override
        public void beforeShutdown()
        {
            shutdownInvoked = true;
        }

        boolean isShutdownInvoked()
        {
            return shutdownInvoked;
        }
    }
}