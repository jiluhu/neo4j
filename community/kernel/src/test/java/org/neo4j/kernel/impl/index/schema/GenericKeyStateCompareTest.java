/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.kernel.impl.index.schema;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.neo4j.values.storable.DateTimeValue;
import org.neo4j.values.storable.DateValue;
import org.neo4j.values.storable.DurationValue;
import org.neo4j.values.storable.LocalDateTimeValue;
import org.neo4j.values.storable.LocalTimeValue;
import org.neo4j.values.storable.TimeValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericKeyStateCompareTest
{

    @Test
    void compareGenericKeyState()
    {
        List<Value> allValues = Arrays.asList(
                Values.of( "string1" ),
                Values.of( 42 ),
                Values.of( true ),
                Values.of( new String[]{"arrayString1", "arraysString2"} ),
                Values.of( new long[]{314, 1337} ), // todo add the other number array types
                Values.of( new boolean[]{false, true} ),
                DateValue.epochDate( 2 ),
                LocalTimeValue.localTime( 100000 ),
                TimeValue.time( 43_200_000_000_000L, ZoneOffset.UTC ), // Noon
                TimeValue.time( 43_201_000_000_000L, ZoneOffset.UTC ),
                TimeValue.time( 43_200_000_000_000L, ZoneOffset.of( "+01:00" ) ), // Noon in the next time-zone
                TimeValue.time( 46_800_000_000_000L, ZoneOffset.UTC ), // Same time UTC as prev time
                LocalDateTimeValue.localDateTime( 2018, 3, 1, 13, 50, 42, 1337 ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 45, 13, 7474, "UTC" ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 45, 13, 7474, "Europe/Stockholm" ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 45, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2015, 3, 25, 12, 45, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 4, 25, 12, 45, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 3, 26, 12, 45, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 3, 25, 13, 45, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 46, 13, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 45, 14, 7474, "+05:00" ),
                DateTimeValue.datetime( 2014, 3, 25, 12, 45, 13, 7475, "+05:00" ),
                // only runnable it JVM supports East-Saskatchewan
                // DateTimeValue.datetime( 2001, 1, 25, 11, 11, 30, 0, "Canada/East-Saskatchewan" ),
                DateTimeValue.datetime( 2038, 1, 18, 9, 14, 7, 0, "-18:00" ),
                DateTimeValue.datetime( 10000, 100, ZoneOffset.ofTotalSeconds( 3 ) ),
                DateTimeValue.datetime( 10000, 101, ZoneOffset.ofTotalSeconds( -3 ) ),
                DurationValue.duration( 10, 20, 30, 40 ),
                DurationValue.duration( 11, 20, 30, 40 ),
                DurationValue.duration( 10, 21, 30, 40 ),
                DurationValue.duration( 10, 20, 31, 40 ),
                DurationValue.duration( 10, 20, 30, 41 ) );
//                Values.pointValue( CoordinateReferenceSystem.Cartesian, 0, 0 ),
//                Values.pointValue( CoordinateReferenceSystem.WGS84, 12.78, 56.7 ) // todo add when spatial is supported
        allValues.sort( Values.COMPARATOR );

        List<GenericKeyState> states = new ArrayList<>();
        for ( Value value : allValues )
        {
            GenericKeyState state = new GenericKeyState();
            state.writeValue( value, NativeIndexKey.Inclusion.NEUTRAL );
            states.add( state );
        }
        Collections.shuffle( states );
        states.sort( GenericKeyState::compareValueTo );
        List<Value> sortedStatesAsValues = states.stream().map( GenericKeyState::asValue ).collect( Collectors.toList() );
        assertEquals( allValues, sortedStatesAsValues );
    }
}
