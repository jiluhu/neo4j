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
package org.neo4j.kernel.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.io.IOException;

import org.neo4j.configuration.Config;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.extension.DefaultFileSystemExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.extension.pagecache.PageCacheSupportExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_TX_LOGS_ROOT_DIR_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.transaction_logs_root_path;
import static org.neo4j.configuration.LayoutConfig.of;

@ExtendWith( {DefaultFileSystemExtension.class, TestDirectoryExtension.class} )
class RecoveryRequiredCheckerTest
{
    @RegisterExtension
    static PageCacheSupportExtension pageCacheExtension = new PageCacheSupportExtension();
    @Inject
    private TestDirectory testDirectory;
    @Inject
    private FileSystemAbstraction fileSystem;

    private File storeDir;
    private DatabaseLayout databaseLayout;
    private final StorageEngineFactory storageEngineFactory = StorageEngineFactory.selectStorageEngine();

    @BeforeEach
    void setup()
    {
        databaseLayout = testDirectory.databaseLayout();
        storeDir = databaseLayout.databaseDirectory();
    }

    @Test
    void shouldNotWantToRecoverIntactStore() throws Exception
    {
        startStopDatabase( fileSystem, storeDir );

        PageCache pageCache = pageCacheExtension.getPageCache( fileSystem );
        RecoveryRequiredChecker recoverer = getRecoveryCheckerWithDefaultConfig( fileSystem, pageCache, storageEngineFactory );

        assertThat( recoverer.isRecoveryRequiredAt( databaseLayout ), is( false ) );
    }

    @Test
    void shouldWantToRecoverBrokenStore() throws Exception
    {
        try ( EphemeralFileSystemAbstraction ephemeralFs = createAndCrashWithDefaultConfig() )
        {
            PageCache pageCache = pageCacheExtension.getPageCache( ephemeralFs );
            RecoveryRequiredChecker recoverer = getRecoveryCheckerWithDefaultConfig( ephemeralFs, pageCache, storageEngineFactory );

            assertThat( recoverer.isRecoveryRequiredAt( databaseLayout ), is( true ) );
        }
    }

    @Test
    void shouldBeAbleToRecoverBrokenStore() throws Exception
    {
        try ( EphemeralFileSystemAbstraction ephemeralFs = createAndCrashWithDefaultConfig() )
        {
            PageCache pageCache = pageCacheExtension.getPageCache( ephemeralFs );

            RecoveryRequiredChecker recoverer = getRecoveryCheckerWithDefaultConfig( ephemeralFs, pageCache, storageEngineFactory );

            assertThat( recoverer.isRecoveryRequiredAt( databaseLayout ), is( true ) );

            startStopDatabase( ephemeralFs, storeDir );

            assertThat( recoverer.isRecoveryRequiredAt( databaseLayout ), is( false ) );
        }
    }

    @Test
    void shouldBeAbleToRecoverBrokenStoreWithLogsInSeparateAbsoluteLocation() throws Exception
    {
        File customTransactionLogsLocation = testDirectory.directory( DEFAULT_TX_LOGS_ROOT_DIR_NAME );
        Config config = Config.builder().withSetting( transaction_logs_root_path,
                customTransactionLogsLocation.getAbsolutePath() ).build();
        recoverBrokenStoreWithConfig( config );
    }

    @Test
    void shouldNotWantToRecoverEmptyStore() throws Exception
    {
        DatabaseLayout databaseLayout = DatabaseLayout.of( testDirectory.directory( "dir-without-store" ) );

        PageCache pageCache = pageCacheExtension.getPageCache( fileSystem );
        RecoveryRequiredChecker checker = getRecoveryCheckerWithDefaultConfig( fileSystem, pageCache, storageEngineFactory );

        assertFalse( checker.isRecoveryRequiredAt( databaseLayout ) );
    }

    @Test
    void shouldWantToRecoverStoreWithoutOneIdFile() throws Exception
    {
        startStopDatabase( fileSystem, storeDir );
        assertAllIdFilesExist();

        PageCache pageCache = pageCacheExtension.getPageCache( fileSystem );
        RecoveryRequiredChecker checker = getRecoveryCheckerWithDefaultConfig( fileSystem, pageCache, storageEngineFactory );
        assertFalse( checker.isRecoveryRequiredAt( databaseLayout ) );

        fileSystem.deleteFileOrThrow( databaseLayout.idNodeStore() );

        assertTrue( checker.isRecoveryRequiredAt( databaseLayout ) );
    }

    @Test
    void shouldWantToRecoverStoreWithoutAllIdFiles() throws Exception
    {
        startStopDatabase( fileSystem, storeDir );
        assertAllIdFilesExist();

        PageCache pageCache = pageCacheExtension.getPageCache( fileSystem );
        RecoveryRequiredChecker checker = getRecoveryCheckerWithDefaultConfig( fileSystem, pageCache, storageEngineFactory );
        assertFalse( checker.isRecoveryRequiredAt( databaseLayout ) );

        for ( File idFile : databaseLayout.idFiles() )
        {
            fileSystem.deleteFileOrThrow( idFile );
        }

        assertTrue( checker.isRecoveryRequiredAt( databaseLayout ) );
    }

    private void recoverBrokenStoreWithConfig( Config config ) throws IOException
    {
        try ( EphemeralFileSystemAbstraction ephemeralFs = createSomeDataAndCrash( storeDir, config ) )
        {
            PageCache pageCache = pageCacheExtension.getPageCache( ephemeralFs );

            RecoveryRequiredChecker recoveryChecker = getRecoveryChecker( ephemeralFs, pageCache, storageEngineFactory, config );

            assertThat( recoveryChecker.isRecoveryRequiredAt( testDirectory.databaseLayout( of( config ) ) ), is( true ) );

            new TestGraphDatabaseFactory()
                    .setFileSystem( ephemeralFs )
                    .newEmbeddedDatabaseBuilder( storeDir )
                    .setConfig( config.getRaw() )
                    .newGraphDatabase()
                    .shutdown();

            assertThat( recoveryChecker.isRecoveryRequiredAt( databaseLayout ), is( false ) );
        }
    }

    private EphemeralFileSystemAbstraction createAndCrashWithDefaultConfig() throws IOException
    {
        return createSomeDataAndCrash( storeDir, Config.defaults() );
    }

    private void assertAllIdFilesExist()
    {
        for ( File idFile : databaseLayout.idFiles() )
        {
            assertTrue( fileSystem.fileExists( idFile ), "ID file " + idFile + " does not exist" );
        }
    }

    private static RecoveryRequiredChecker getRecoveryCheckerWithDefaultConfig( FileSystemAbstraction fileSystem, PageCache pageCache,
            StorageEngineFactory storageEngineFactory )
    {
        return getRecoveryChecker( fileSystem, pageCache, storageEngineFactory, Config.defaults() );
    }

    private static RecoveryRequiredChecker getRecoveryChecker( FileSystemAbstraction fileSystem, PageCache pageCache,
            StorageEngineFactory storageEngineFactory, Config config )
    {
        return new RecoveryRequiredChecker( fileSystem, pageCache, config, storageEngineFactory );
    }

    private static EphemeralFileSystemAbstraction createSomeDataAndCrash( File store, Config config ) throws IOException
    {
        try ( EphemeralFileSystemAbstraction ephemeralFs = new EphemeralFileSystemAbstraction() )
        {
            final GraphDatabaseService db = new TestGraphDatabaseFactory()
                    .setFileSystem( ephemeralFs )
                    .newEmbeddedDatabaseBuilder( store )
                    .setConfig( config.getRaw() )
                    .newGraphDatabase();

            try ( Transaction tx = db.beginTx() )
            {
                db.createNode();
                tx.success();
            }

            EphemeralFileSystemAbstraction snapshot = ephemeralFs.snapshot();
            db.shutdown();
            return snapshot;
        }
    }

    private static void startStopDatabase( FileSystemAbstraction fileSystem, File storeDir )
    {
        new TestGraphDatabaseFactory().setFileSystem( fileSystem ).newEmbeddedDatabase( storeDir ).shutdown();
    }
}