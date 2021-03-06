package com.splout.db.hadoop;

/*
 * #%L
 * Splout SQL Hadoop library
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Test;

import com.datasalt.pangool.io.Fields;
import com.datasalt.pangool.io.ITuple;
import com.datasalt.pangool.io.Schema;
import com.datasalt.pangool.io.Tuple;
import com.datasalt.pangool.io.TupleFile;
import com.datasalt.pangool.tuplemr.mapred.lib.input.TupleInputFormat;
import com.splout.db.hadoop.TupleSampler.SamplingType;
import com.splout.db.hadoop.TupleSampler.TupleSamplerException;

public class TestTupleSampler {

	public static String INPUT = "input-" + TestTupleSampler.class.getName();
	public static String OUTPUT = "output-" + TestTupleSampler.class.getName();

	@Test
	public void testDefault() throws IOException, InterruptedException, TupleSamplerException {
		testDefault(Long.MAX_VALUE);
		testDefault(1024 * 100);
		testDefault(1024 * 10);
	}
	
	@Test
	public void testReservoir() throws IOException, InterruptedException, TupleSamplerException {
		Runtime.getRuntime().exec("rm -rf " + INPUT);
		Runtime.getRuntime().exec("rm -rf " + OUTPUT);
		
		Configuration conf = new Configuration();

		final Schema schema = new Schema("schema", Fields.parse("id:string, foo:int"));
    TupleFile.Writer writer = new TupleFile.Writer(FileSystem.get(conf), conf, new Path(INPUT), schema);
		for(int i = 0; i < 10000; i++) {
			ITuple tuple = new Tuple(schema);
			tuple.set("id", "id" + i);
			// We save a number in the "foo" field which is consecutive: [0, 1, 2, ... 9999]
			tuple.set("foo", i);
			writer.append(tuple);
		}
		writer.close();
		
		// Sampling with default method should yield lower numbers
		// Default input split size so only one InputSplit
		// All sampled keys will be [0, 1, 2, ..., 9]
		TupleSampler sampler = new TupleSampler(SamplingType.DEFAULT, new TupleSampler.DefaultSamplingOptions());
		sampler.sample(Arrays.asList(new TableInput[] { new TableInput(new TupleInputFormat(), new HashMap<String, String>(), schema, new IdentityRecordProcessor(), new Path(INPUT)) }), schema, conf, 10, new Path(OUTPUT));		
		
		int nTuples = 0;
		int[] sampledKeys = new int[10];
		
		TupleFile.Reader reader = new TupleFile.Reader(FileSystem.get(conf),  conf, new Path(OUTPUT));
    Tuple tuple = new Tuple(reader.getSchema());
		while(reader.next(tuple)) {
			// Get the "foo" field from sampled Tuples
			sampledKeys[nTuples] = Integer.parseInt(tuple.get("foo").toString());
			nTuples++;
		}
		reader.close();
		
		for(int i = 0; i < 10; i++) {
			assertEquals(i, sampledKeys[i]);
		}

		// Reservoir sampling should yield better results for this case, let's see
		sampler = new TupleSampler(SamplingType.RESERVOIR, new TupleSampler.DefaultSamplingOptions());
		sampler.sample(Arrays.asList(new TableInput[] { new TableInput(new TupleInputFormat(), new HashMap<String, String>(), schema, new IdentityRecordProcessor(), new Path(INPUT)) }), schema, conf, 10, new Path(OUTPUT));
		
		nTuples = 0;
		sampledKeys = new int[10];

    reader = new TupleFile.Reader(FileSystem.get(conf), conf, new Path(OUTPUT));
    tuple = new Tuple(reader.getSchema());
    while(reader.next(tuple)) {
      // Get the "foo" field from sampled Tuples
			sampledKeys[nTuples] = Integer.parseInt(tuple.get("foo").toString());
			nTuples++;
		}
		reader.close();
		
		int avgKey = 0;
		for(int i = 0; i < 10; i++) {
			avgKey += sampledKeys[i];
		}
		
		avgKey = avgKey / 10;
		// This assertion may fail some day... but the chances are very rare.
		// The lower bound is very low, usually the average key will be around 1/4th of the max key (10000).
		assertTrue(avgKey > 100);
		
		Runtime.getRuntime().exec("rm -rf " + INPUT);
		Runtime.getRuntime().exec("rm -rf " + OUTPUT);
	}
	
	public void testDefault(long splitSize) throws TupleSamplerException, IOException, InterruptedException {
		Runtime.getRuntime().exec("rm -rf " + INPUT);
		Runtime.getRuntime().exec("rm -rf " + OUTPUT);
		
		Configuration conf = new Configuration();

    TupleFile.Writer writer = new TupleFile.Writer(FileSystem.get(conf), conf, new Path(INPUT), schema);
		for(int i = 0; i < 1000; i++) {
			writer.append(randomTuple());
		}
		writer.close();

    // Requesting as many samples as splits so one sample is needed from each split.
    FileStatus status = FileSystem.get(conf).getFileStatus(new Path(INPUT));
    long expectedSplits = Math.max(1, (long) Math.ceil(((double) status.getLen()) / splitSize));

		TupleSampler.DefaultSamplingOptions options = new TupleSampler.DefaultSamplingOptions();
		options.setMaxInputSplitSize(splitSize);
    options.setMaxSplitsToVisit(Integer.MAX_VALUE);
		
		TupleSampler sampler = new TupleSampler(SamplingType.DEFAULT, options);
		sampler.sample(Arrays.asList(new TableInput[] { new TableInput(new TupleInputFormat(), new HashMap<String, String>(), schema, new IdentityRecordProcessor(), new Path(INPUT)) }), schema, conf, expectedSplits, new Path(OUTPUT));
		int nTuples = 0;
    TupleFile.Reader reader = new TupleFile.Reader(FileSystem.get(conf), conf, new Path(OUTPUT));
    Tuple tuple = new Tuple(reader.getSchema());
    while(reader.next(tuple)) {
			nTuples++;
		}
		reader.close();
		
		String a = "";
		a.split("foo");
		
		assertEquals(expectedSplits, nTuples);
		
		Runtime.getRuntime().exec("rm -rf " + INPUT);
		Runtime.getRuntime().exec("rm -rf " + OUTPUT);
	}

	final Schema schema = new Schema("schema", Fields.parse("id:string, foo:string"));

	public ITuple randomTuple() {
		ITuple tuple = new Tuple(schema);
		tuple.set("id", "id" + (Math.random() * 1000000000));
		tuple.set("foo", "foobar" + (Math.random() * 1000000000));
		return tuple;
	}
}
