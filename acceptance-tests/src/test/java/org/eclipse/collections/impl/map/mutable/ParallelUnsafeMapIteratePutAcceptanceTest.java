/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.map.mutable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.test.Verify;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
public class ParallelUnsafeMapIteratePutAcceptanceTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelUnsafeMapIteratePutAcceptanceTest.class);

    private static final long SEED = 0x12345678ABCDL;

    private static final long PUT_REPEAT = 100;
    private static final int CHUNK_SIZE = 16000;

    @After
    @TearDown(Level.Trial)
    public void tearDown()
    {
        fullGc();
    }

    private static void fullGc()
    {
        System.gc();
        Thread.yield();
        System.gc();
        try
        {
            Thread.sleep(100);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Benchmark
    public void testMapIteratePut()
    {
        int constSize = 1000000;
        int size = 100000000;
        Integer[] contents = new Integer[size];
        Integer[] constContents = new Integer[constSize];
        for (int i = 0; i < size; i++)
        {
            contents[i] = i;
            if (i < constSize / 2)
            {
                constContents[i] = i;
            }
            else if (i < constSize)
            {
                constContents[i] = size - i;
            }
        }
        Collections.shuffle(Arrays.asList(contents), new Random(SEED));
        this.runAllPutTests(contents, constContents);
    }
    
    @Param({"10", "50", "100", "500", "1000", "5000", "10000"})
    private int threads;

    private void runAllPutTests(Integer[] contents, Integer[] constContents)
    {
        ExecutorService executorService = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<>(threads));
        this.runPutTest1(threads, contents, constContents, executorService, false);
        executorService.shutdown();
    }

    private void runPutTest1(int threadCount, Integer[] contents, Integer[] constContents, ExecutorService executorService, boolean warmup)
    {
        long ops = ((warmup ? 1000000 : 1000000 * PUT_REPEAT) / contents.length) + 1;
        Future<?>[] futures = new Future<?>[threadCount];
        for (int i = 0; i < ops; i++)
        {
            ConcurrentHashMapUnsafe<Integer, Integer> map = new ConcurrentHashMapUnsafe<>(constContents.length);
            UnifiedSet<Integer> setToRemove = UnifiedSet.newSet(constContents.length);
            for (Integer x : constContents)
            {
                map.put(x, x);
                setToRemove.put(x);
            }
            AtomicInteger currentPos = new AtomicInteger();
            for (int t = 0; t < threadCount; t++)
            {
                futures[t] = executorService.submit(new PutRunner1(map, contents, currentPos));
            }
            int count = 0;
            UnifiedSet<Integer> setToAdd = UnifiedSet.newSet(constContents.length);
            for (Integer next : map.keySet())
            {
                setToRemove.remove(next);
                Assert.assertTrue(setToAdd.add(next));
                count++;
            }
            Assert.assertTrue(count >= constContents.length);
            Verify.assertEmpty(setToRemove);
            for (Future<?> future : futures)
            {
                try
                {
                    future.get();
                }
                catch (ExecutionException | InterruptedException e)
                {
                    throw new RuntimeException("unexpected", e);
                }
            }
            if (map.size() != contents.length)
            {
                throw new AssertionError();
            }
        }
    }

    private static final class PutRunner1 implements Runnable
    {
        private final Map<Integer, Integer> map;
        private final Integer[] contents;
        private long total;
        private final AtomicInteger queuePosition;

        private PutRunner1(Map<Integer, Integer> map, Integer[] contents, AtomicInteger queuePosition)
        {
            this.map = map;
            this.contents = contents;
            this.queuePosition = queuePosition;
        }

        @Override
        public void run()
        {
            while (this.queuePosition.get() < this.contents.length)
            {
                int end = this.queuePosition.addAndGet(CHUNK_SIZE);
                int start = end - CHUNK_SIZE;
                if (start < this.contents.length)
                {
                    if (end > this.contents.length)
                    {
                        end = this.contents.length;
                    }
                    for (int i = start; i < end; i++)
                    {
                        if (this.map.put(this.contents[i], this.contents[i]) != null)
                        {
                            this.total++;
                        }
                    }
                }
                LOGGER.info("Processed chunk ending at: {}", end);
            }
            if (this.total < 0)
            {
                throw new AssertionError("never gets here, but it can't be optimized away");
            }
        }
    }
}
