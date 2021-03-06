package com.alibaba.otter.canal.sink.entry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.position.LogIdentity;
import com.alibaba.otter.canal.sink.AbstractCanalEventSink;
import com.alibaba.otter.canal.sink.CanalEventDownStreamHandler;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.sink.exception.CanalSinkException;
import com.alibaba.otter.canal.store.CanalEventStore;
import com.alibaba.otter.canal.store.model.Event;

/**
 * mysql binlog数据对象输出
 * 
 * @author jianghang 2012-7-4 下午03:23:16
 * @version 1.0.0
 */
public class EntryEventSink extends AbstractCanalEventSink<List<CanalEntry.Entry>> implements CanalEventSink<List<CanalEntry.Entry>> {

    private static final Logger    logger                      = LoggerFactory.getLogger(EntryEventSink.class);
    private static final int       maxFullTimes                = 10;
    private CanalEventStore<Event> eventStore;
    protected boolean              filterTransactionEntry      = false;                                        // 是否需要过滤事务头/尾
    protected boolean              filterEmtryTransactionEntry = true;                                         // 是否需要过滤空的事务头/尾
    protected AtomicBoolean        isInterrupt                 = new AtomicBoolean(false);

    public void start() {
        super.start();
        Assert.notNull(eventStore);
    }

    public void stop() {
        super.stop();
    }

    public boolean filter(List<Entry> event, InetSocketAddress remoteAddress, String destination) {

        return false;
    }

    public boolean sink(List<CanalEntry.Entry> entrys, InetSocketAddress remoteAddress, String destination)
                                                                                                           throws CanalSinkException,
                                                                                                           InterruptedException {
        List rowDatas = entrys;
        if (filterTransactionEntry) {
            rowDatas = new ArrayList<CanalEntry.Entry>();
            for (CanalEntry.Entry entry : entrys) {
                if (entry.getEntryType() == EntryType.ROWDATA) {
                    rowDatas.add(entry);
                }
            }
        }

        return sinkData(rowDatas, remoteAddress);
    }

    private boolean sinkData(List<CanalEntry.Entry> entrys, InetSocketAddress remoteAddress)
                                                                                            throws InterruptedException {
        List<Event> events = new ArrayList<Event>();
        for (CanalEntry.Entry entry : entrys) {
            Event event = new Event(new LogIdentity(remoteAddress, -1L), entry);
            if (!doFilter(event)) {
                continue;
            }

            events.add(event);
        }

        return doSink(events);
    }

    protected boolean doFilter(Event event) {
        if (filter != null && event.getEntry().getEntryType() == EntryType.ROWDATA) {
            String name = getSchemaNameAndTableName(event.getEntry());
            boolean need = filter.filter(name);
            if (!need) {
                logger.debug("filter name[{}] entry : {}:{}",
                             new Object[] { name, event.getEntry().getHeader().getLogfileName(),
                                     event.getEntry().getHeader().getLogfileOffset() });
            }

            return need;
        } else {
            return true;
        }
    }

    protected boolean doSink(List<Event> events) {
        for (CanalEventDownStreamHandler<Event> handler : getHandlers()) {
            handler.before(events);
        }

        int fullTimes = 0;
        do {
            if (eventStore.tryPut(events)) {
                for (CanalEventDownStreamHandler<Event> handler : getHandlers()) {
                    handler.after(events);
                }
                return true;
            } else {
                applyWait(++fullTimes);
            }

            for (CanalEventDownStreamHandler<Event> handler : getHandlers()) {
                handler.retry(events);
            }
            // isInterrupt 只会被响应一次，一旦true改为false后，就会退出无限的tryPut操作
            if (isInterrupt.compareAndSet(true, false)) {
                return false;
            }
        } while (running && !Thread.currentThread().isInterrupted());
        return false;
    }

    // 处理无数据的情况，避免空循环挂死
    private void applyWait(int fullTimes) {
        int newFullTimes = fullTimes > maxFullTimes ? maxFullTimes : fullTimes;
        if (fullTimes <= 3) { // 3次以内
            Thread.yield();
        } else { // 超过3次，最多只sleep 10ms
            LockSupport.parkNanos(1000 * 1000L * newFullTimes);
        }

    }

    private String getSchemaNameAndTableName(CanalEntry.Entry entry) {
        StringBuilder result = new StringBuilder();
        result.append(entry.getHeader().getSchemaName()).append(".").append(entry.getHeader().getTableName());
        return result.toString();
    }

    public void interrupt() {
        super.interrupt();
        isInterrupt.compareAndSet(false, true); // 设置为中断状态
    }

    public void setEventStore(CanalEventStore<Event> eventStore) {
        this.eventStore = eventStore;
    }

    public void setFilterTransactionEntry(boolean filterTransactionEntry) {
        this.filterTransactionEntry = filterTransactionEntry;
    }

    public void setFilterEmtryTransactionEntry(boolean filterEmtryTransactionEntry) {
        this.filterEmtryTransactionEntry = filterEmtryTransactionEntry;
    }

}
