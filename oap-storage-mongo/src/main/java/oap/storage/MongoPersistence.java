/*
 * The MIT License (MIT)
 *
 * Copyright (c) Open Application Platform Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package oap.storage;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Threads;
import oap.concurrent.scheduler.PeriodicScheduled;
import oap.concurrent.scheduler.Scheduled;
import oap.concurrent.scheduler.Scheduler;
import oap.reflect.TypeRef;
import oap.storage.mongo.JsonCodec;
import oap.storage.mongo.MongoClient;
import oap.storage.mongo.OplogService;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.eq;

/**
 * Mongo Persistence storage (similar to {@link oap.storage.DirectoryPersistence})
 *
 * @param <T> Type of Metadata
 */
@Slf4j
public class MongoPersistence<T> implements Closeable, Runnable, OplogService.OplogListener {

    public static final ReplaceOptions REPLACE_OPTIONS_UPSERT = new ReplaceOptions().upsert( true );
    public final MongoCollection<Metadata<T>> collection;
    private final Lock lock = new ReentrantLock();
    public OplogService oplogService;
    public int batchSize = 100;
    private long delay;
    private MemoryStorage<T> storage;
    private PeriodicScheduled scheduled;

    public MongoPersistence( MongoClient mongoClient,
                             String table,
                             long delay,
                             MemoryStorage<T> storage ) {
        this.delay = delay;
        this.storage = storage;

        TypeRef<Metadata<T>> ref = new TypeRef<>() {};

        CodecRegistry codecRegistry = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs( new JsonCodec<>( ref, m -> this.storage.identifier.get( m.object ) ) ),
            mongoClient.database.getCodecRegistry()
        );

        this.collection = mongoClient.database
            .getCollection( table, ref.clazz() )
            .withCodecRegistry( codecRegistry );
    }

    @Override
    public void run() {
        fsync( scheduled.lastExecuted() );
    }

    public void start() {
        Threads.synchronously( lock, () -> {
            this.load();
            this.scheduled = Scheduler.scheduleWithFixedDelay( getClass(), delay, this::fsync );
            if( oplogService != null ) oplogService.addListener( collection.getNamespace().getCollectionName(), this );
        } );
    }

    /**
     * Load all {@link MongoCollection} documents to the {@link MemoryStorage#data}
     */
    private void load() {
        MongoNamespace namespace = collection.getNamespace();
        log.debug( "loading data from {}", namespace );
        final Consumer<Metadata<T>> cons = metadata -> {
            var id = storage.identifier.get( metadata.object );
            storage.data.put( id, metadata );
        };
        log.info( "Load {} documents from [{}] Mongo namespace", collection.countDocuments(), namespace );
        collection.find().forEach( cons );
        log.info( storage.data.size() + " object(s) loaded." );
    }

    /**
     * It persists the objects modified earlier than {@code last} to MongoDB
     *
     * @param last executed sync
     *             TODO: avoid explicit usage of fsync
     */
    private void fsync( long last ) {
        Threads.synchronously( lock, () -> {
            log.trace( "fsyncing, last: {}, storage length: {}", last, storage.data.size() );
            var list = new ArrayList<WriteModel<Metadata<T>>>( batchSize );
            var deletedIds = new ArrayList<String>( batchSize );
            storage.data.forEach( ( id, m ) -> {
                if( m.modified >= last ) {
                    if( m.isDeleted() ) {
                        deletedIds.add( id );
                        list.add( new DeleteOneModel<>( eq( "_id", id ) ) );
                    } else list.add( new ReplaceOneModel<>( eq( "_id", id ), m, REPLACE_OPTIONS_UPSERT ) );
                    if( list.size() >= batchSize ) persist( deletedIds, list );
                }
            } );
            if( !list.isEmpty() ) persist( deletedIds, list );
        } );
    }

    private void persist( List<String> deletedIds, List<WriteModel<Metadata<T>>> list ) {
        collection.bulkWrite( list, new BulkWriteOptions().ordered( false ) );
        deletedIds.forEach( storage::deleteInternalObject );
        deletedIds.clear();
        list.clear();
    }

    @Override
    public void close() {
        log.debug( "Closing {}...", this );
        if( scheduled != null && storage != null ) {
            Threads.synchronously( lock, () -> {
                Scheduled.cancel( scheduled );
                fsync( scheduled.lastExecuted() );
                log.debug( "Closed {}", this );
            } );
        } else {
            log.debug( "This {} was't started or already closed", this );
        }
    }

    @Override
    public void updated( String table, String id ) {
        refresh( id );
    }

    @Override
    public void deleted( String table, String id ) {
        storage.delete( id );
    }

    @Override
    public void inserted( String table, String id ) {
        refresh( id );
    }

    public void refresh( String id ) {
        var m = collection.find( eq( "_id", id ) ).first();
        if( m != null ) {
            storage.lock.synchronizedOn( id, () -> {
                var oldM = storage.data.get( id );
                if( oldM == null || m.modified > oldM.modified ) {
                    log.debug( "refresh from mongo {}", id );
                    storage.data.put( id, m );
                    storage.fireUpdated( m.object, false );
                } else {
                    log.debug( "[{}] m.modified <= oldM.modified", id );
                }
            } );
        }
    }
}
