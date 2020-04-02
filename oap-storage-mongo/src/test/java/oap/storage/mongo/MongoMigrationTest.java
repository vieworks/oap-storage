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

package oap.storage.mongo;

import oap.testng.Env;
import oap.testng.Fixtures;
import org.testng.annotations.Test;

import java.io.IOException;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

public class MongoMigrationTest extends Fixtures {
    private final MongoFixture mongoFixture;

    public MongoMigrationTest() {
        fixture( mongoFixture = new MongoFixture() );
    }

    @Test
    public void migration() throws IOException {
        var migration = new MongoMigration( Env.deployTestData( getClass() ) );
        migration.variables.put( "testB", "true" );
        migration.variables.put( "testS", "\"true\"" );

        migration.run( mongoFixture.mongoClient );

        var version = mongoFixture.mongoClient.database.getCollection( "version" ).find( eq( "_id", "version" ) ).first();
        assertThat( version ).isNotNull();
        assertThat( version.get( "value" ) ).isEqualTo( 10 );

        assertValue( "test", "test", "c", 17 );
        assertValue( "test", "test3", "v", 1 );

        migration.run( mongoFixture.mongoClient );
        assertValue( "test", "test", "c", 17 );
        assertValue( "test", "test3", "v", 1 );
    }

    public void assertValue( String collection, String id, String field, int expected ) {
        assertThat(
            mongoFixture.mongoClient.database.getCollection( collection ).find( eq( "_id", id ) )
                .first()
                .getInteger( field ) )
            .isEqualTo( expected );
    }
}
