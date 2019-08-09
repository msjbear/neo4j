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
package org.neo4j.kernel.impl.storemigration;

import org.eclipse.collections.api.iterator.MutableLongIterator;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.stream.Stream;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.IdType;
import org.neo4j.internal.id.ScanOnOpenOverwritingIdGeneratorFactory;
import org.neo4j.internal.recordstorage.RandomSchema;
import org.neo4j.internal.recordstorage.SchemaStorage;
import org.neo4j.internal.recordstorage.StoreTokens;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.SchemaRule;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.SchemaStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_4;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.storemigration.legacy.SchemaStore35;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StoreVersionCheck;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.storageengine.migration.MigrationProgressMonitor;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.token.TokenHolders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@PageCacheExtension
class RecordStorageMigratorIT
{
    private static final String MIGRATION_DIRECTORY = "upgrade";
    private static final Config CONFIG = Config.defaults( GraphDatabaseSettings.pagecache_memory, "8m" );

    @Inject
    private TestDirectory directory;
    @Inject
    private PageCache pageCache;

    private final MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
    private final JobScheduler jobScheduler = new ThreadPoolJobScheduler();

    private static Stream<Arguments> versions()
    {
        return Stream.of(
            Arguments.of(
                StandardV3_4.STORE_VERSION,
                new LogPosition( 3, 385 ),
                txInfoAcceptanceOnIdAndTimestamp( 42, 1548441268467L ) ) );
    }

    @AfterEach
    void tearDown() throws Exception
    {
        jobScheduler.close();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldBeAbleToResumeMigrationOnMoving( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator )
        throws Exception
    {
        // GIVEN a legacy database
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );

        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ), versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // WHEN simulating resuming the migration
        migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        migrator.moveMigratedFiles( migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // THEN starting the new store should be successful
        StoreFactory storeFactory = new StoreFactory(
                databaseLayout, CONFIG, new ScanOnOpenOverwritingIdGeneratorFactory( fs ), pageCache, fs, logService.getInternalLogProvider() );
        storeFactory.openAllNeoStores().close();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldBeAbleToMigrateWithoutErrors( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator ) throws Exception
    {
        // GIVEN a legacy database
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );

        AssertableLogProvider logProvider = new AssertableLogProvider( true );
        LogService logService = new SimpleLogService( logProvider, logProvider );

        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );

        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );

        // WHEN migrating
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ), versionToMigrateFrom, getVersionToMigrateTo( check ) );
        migrator.moveMigratedFiles( migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // THEN starting the new store should be successful
        StoreFactory storeFactory = new StoreFactory(
                databaseLayout, CONFIG, new ScanOnOpenOverwritingIdGeneratorFactory( fs ), pageCache, fs,
                logService.getInternalLogProvider() );
        storeFactory.openAllNeoStores().close();
        logProvider.rawMessageMatcher().assertNotContains( "ERROR" );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldBeAbleToResumeMigrationOnRebuildingCounts( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator )
        throws Exception
    {
        // GIVEN a legacy database
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );

        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ),
                versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // WHEN simulating resuming the migration

        migrator.moveMigratedFiles( migrationLayout, databaseLayout, versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // THEN starting the new store should be successful
        StoreFactory storeFactory =
                new StoreFactory( databaseLayout, CONFIG, new ScanOnOpenOverwritingIdGeneratorFactory( fs ), pageCache, fs,
                        logService.getInternalLogProvider() );
        storeFactory.openAllNeoStores().close();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldComputeTheLastTxLogPositionCorrectly( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator )
        throws Throwable
    {
        // GIVEN a legacy database
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );

        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );

        // WHEN migrating
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ), versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // THEN it should compute the correct last tx log position
        assertEquals( expectedLogPosition, migrator.readLastTxLogPosition( migrationLayout ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldComputeTheLastTxInfoCorrectly( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator )
        throws Exception
    {
        // given
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();
        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );

        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );

        // when
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ), versionToMigrateFrom, getVersionToMigrateTo( check ) );

        // then
        assertTrue( txIdComparator.apply( migrator.readLastTxInformation( migrationLayout ) ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void mustMigrateSchemaStoreToNewFormat( String version, LogPosition expectedLogPosition, Function<TransactionId, Boolean> txIdComparator ) throws Exception
    {
        // Given we have an old store full of random schema rules.
        DatabaseLayout databaseLayout = directory.databaseLayout();
        File prepare = directory.directory( "prepare" );
        var fs = directory.getFileSystem();
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, databaseLayout.databaseDirectory(), prepare );
        // and a state of the migration saying that it has done the actual migration
        LogService logService = NullLogService.getInstance();

        // Uses this special scan-on-open IGF because when the new IndexedIdGenerator was introduced this test would break
        // when trying to open an older store, before doing migration.
        IdGeneratorFactory igf = new ScanOnOpenOverwritingIdGeneratorFactory( fs );
        LogProvider logProvider = logService.getInternalLogProvider();
        File storeFile = databaseLayout.schemaStore();
        File idFile = databaseLayout.idSchemaStore();
        SchemaStore35 schemaStore35 = new SchemaStore35( storeFile, idFile, CONFIG, IdType.SCHEMA, igf, pageCache, logProvider, StandardV3_4.RECORD_FORMATS );
        schemaStore35.initialise( false );
        SplittableRandom rng = new SplittableRandom();
        LongHashSet indexes = new LongHashSet();
        LongHashSet constraints = new LongHashSet();
        for ( int i = 0; i < 1000; i++ )
        {
            long id = schemaStore35.nextId();
            MutableLongSet target = rng.nextInt( 3 ) < 2 ? indexes : constraints;
            target.add( id );
        }

        List<SchemaRule> generatedRules = new ArrayList<>();
        RealIdsRandomSchema randomSchema = new RealIdsRandomSchema( rng, indexes, constraints );
        while ( randomSchema.hasMoreIds() )
        {
            try
            {
                SchemaRule schemaRule = randomSchema.nextSchemaRule();
                if ( schemaRule instanceof ConstraintDescriptor )
                {
                    ConstraintDescriptor constraint = (ConstraintDescriptor) schemaRule;
                    if ( constraint.isIndexBackedConstraint() && !constraint.asIndexBackedConstraint().hasOwnedIndexId() )
                    {
                        // Filter out constraints that are supposed to own indexes, but don't, because those are illegal to persist.
                        randomSchema.rollback();
                        continue;
                    }
                }
                randomSchema.commit();
                generatedRules.add( schemaRule );
                List<DynamicRecord> dynamicRecords = schemaStore35.allocateFrom( schemaRule );
                for ( DynamicRecord dynamicRecord : dynamicRecords )
                {
                    schemaStore35.updateRecord( dynamicRecord );
                }
            }
            catch ( NoSuchElementException ignore )
            {
                // We're starting to run low on ids, but just ignore this and loop as along as there are still some left.
            }
        }
        schemaStore35.flush();
        schemaStore35.close();

        RecordStoreVersionCheck check = getVersionCheck( pageCache, databaseLayout );
        String versionToMigrateFrom = getVersionToMigrateFrom( check );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, CONFIG, logService, jobScheduler );
        DatabaseLayout migrationLayout = directory.databaseLayout( MIGRATION_DIRECTORY );

        // When we migrate it to the new store format.
        String versionToMigrateTo = getVersionToMigrateTo( check );
        migrator.migrate( databaseLayout, migrationLayout, progressMonitor.startSection( "section" ), versionToMigrateFrom, versionToMigrateTo );
        migrator.moveMigratedFiles( migrationLayout, databaseLayout, versionToMigrateFrom, versionToMigrateTo );

        generatedRules.sort( Comparator.comparingLong( SchemaRule::getId ) );

        // Then the new store should retain an exact representation of the old-format schema rules.
        StoreFactory storeFactory = new StoreFactory( databaseLayout, CONFIG, igf, pageCache, fs, logProvider );
        try ( NeoStores neoStores = storeFactory.openAllNeoStores() )
        {
            SchemaStore schemaStore = neoStores.getSchemaStore();
            TokenHolders tokenHolders = StoreTokens.readOnlyTokenHolders( neoStores );
            SchemaStorage storage = new SchemaStorage( schemaStore, tokenHolders );
            List<SchemaRule> migratedRules = new ArrayList<>();
            storage.getAll().iterator().forEachRemaining( migratedRules::add );

            assertThat( migratedRules, equalTo( generatedRules ) );
        }
    }

    private static class RealIdsRandomSchema extends RandomSchema
    {
        private final Pair<LongHashSet,LongHashSet> newIndexes;
        private final Pair<LongHashSet,LongHashSet> newConstraints;
        private final Pair<LongHashSet,LongHashSet> existingIndexes;
        private final Pair<LongHashSet,LongHashSet> existingConstraints;

        RealIdsRandomSchema( SplittableRandom rng, LongHashSet indexes, LongHashSet constraints )
        {
            super( rng );
            this.newIndexes = Pair.of( indexes, new LongHashSet() );
            this.newConstraints = Pair.of( constraints, new LongHashSet() );
            this.existingIndexes = Pair.of( new LongHashSet( indexes ), new LongHashSet() );
            this.existingConstraints = Pair.of( new LongHashSet( constraints ), new LongHashSet() );
        }

        @Override
        public long nextRuleIdForIndex()
        {
            return nextRuleId( newIndexes );
        }

        @Override
        public long existingConstraintId()
        {
            return nextRuleId( existingConstraints );
        }

        @Override
        public long nextRuleIdForConstraint()
        {
            return nextRuleId( newConstraints );
        }

        @Override
        public long existingIndexId()
        {
            return nextRuleId( existingIndexes );
        }

        private long nextRuleId( Pair<LongHashSet,LongHashSet> idSet )
        {
            try
            {
                MutableLongIterator itr = idSet.first().longIterator();
                long next = itr.next();
                itr.remove();
                idSet.other().add( next );
                return next;
            }
            catch ( NoSuchElementException exception )
            {
                rollback();
                throw exception;
            }
        }

        public void rollback()
        {
            for ( Pair<LongHashSet,LongHashSet> pair : Arrays.asList( newIndexes, newConstraints, existingIndexes, existingConstraints ) )
            {
                pair.first().addAll( pair.other() );
                pair.other().clear();
            }
        }

        public void commit()
        {
            for ( Pair<LongHashSet,LongHashSet> pair : Arrays.asList( newIndexes, newConstraints, existingIndexes, existingConstraints ) )
            {
                pair.other().clear();
            }
        }

        boolean hasMoreIds()
        {
            return newIndexes.first().notEmpty() || newConstraints.first().notEmpty();
        }
    }

    private String getVersionToMigrateFrom( RecordStoreVersionCheck check )
    {
        StoreVersionCheck.Result result = check.checkUpgrade( check.configuredVersion() );
        assertTrue( result.outcome.isSuccessful() );
        return result.actualVersion;
    }

    private String getVersionToMigrateTo( RecordStoreVersionCheck check )
    {
        return check.configuredVersion();
    }

    private RecordStoreVersionCheck getVersionCheck( PageCache pageCache, DatabaseLayout layout )
    {
        return new RecordStoreVersionCheck( pageCache, layout, selectFormat(), Config.defaults() );
    }

    private static RecordFormats selectFormat()
    {
        return Standard.LATEST_RECORD_FORMATS;
    }

    private static Function<TransactionId,Boolean> txInfoAcceptanceOnIdAndTimestamp( long id, long timestamp )
    {
        return txInfo -> txInfo.transactionId() == id && txInfo.commitTimestamp() == timestamp;
    }
}