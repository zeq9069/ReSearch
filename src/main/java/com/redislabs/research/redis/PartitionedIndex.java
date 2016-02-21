package com.redislabs.research.redis;

import com.redislabs.research.Document;
import com.redislabs.research.Index;
import com.redislabs.research.Query;
import com.redislabs.research.Spec;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/**
 * PartitionedIndex wraps multiple partitions of simple indexes and queries them concurrently
 */
public class PartitionedIndex implements Index {

    Index[] partitions;
    ExecutorService pool;
    int timeoutMilli;

    public PartitionedIndex(String name, Spec spec, int numPartitions, int timeoutMilli, String ...redisURIs ) {

        partitions = new Index[numPartitions];
        this.timeoutMilli = timeoutMilli;
        for (int i =0; i < numPartitions; i++) {
            String pname = String.format("%s{%d}", name, i);
            partitions[i] = new SimpleIndex(redisURIs[i % redisURIs.length], pname, spec);
        }

        pool = Executors.newFixedThreadPool(numPartitions*2);


    }

    private CRC32 hash = new CRC32();
    synchronized int partitionFor(String id) {
        hash.reset();
        hash.update(id.getBytes());
        return (int) (hash.getValue() % partitions.length);
    }

    @Override
    public Boolean index(Document  ...docs) {

        // TODO: Make this transactional and pipelined
        for (Document doc : docs) {
            partitions[partitionFor(doc.id())].index(doc);
        }

        return true;
    }

    @Override
    public List<String> get(final Query q) throws IOException, InterruptedException {

        // this is the queue we use to aggregate the results
        final ArrayBlockingQueue<List<String>> queue = new ArrayBlockingQueue<>(partitions.length);

        // submit the sub tasks to the thread pool
        for (Index idx : partitions) {
            final Index fidx = idx;

            pool.submit( new Callable<Void>() {
                public Void call() throws IOException, InterruptedException {
                    List<String> r = fidx.get(q);
                    System.out.printf("Putting %d results in queue", r.size());
                    queue.put(r);
                    return null;
                }
            });

        }


        // collect the results
        List<String> ret = new ArrayList<>(q.sort.offset + q.sort.limit);
        int took = 0;
        while (ret.size() < q.sort.offset + q.sort.limit && took < partitions.length) {

            List<String> res = queue.poll(timeoutMilli, TimeUnit.MILLISECONDS);
            ret.addAll(res);
            took++;

        }
        return ret.subList(q.sort.offset, Math.min(q.sort.offset+q.sort.limit, ret.size()));

    }

    @Override
    public Boolean delete(String... ids) {


        for (Index idx : partitions) {
            idx.delete(ids);
        }

        return true;


    }

    @Override
    public Boolean drop() {

        for (Index idx : partitions) {
            idx.drop();
        }

        return true;

    }
}
