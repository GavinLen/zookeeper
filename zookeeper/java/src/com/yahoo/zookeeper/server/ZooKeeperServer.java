/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.yahoo.jute.BinaryInputArchive;
import com.yahoo.jute.BinaryOutputArchive;
import com.yahoo.jute.InputArchive;
import com.yahoo.jute.Record;
import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.data.ACL;
import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.RequestHeader;
import com.yahoo.zookeeper.server.SessionTracker.SessionExpirer;
import com.yahoo.zookeeper.server.quorum.Leader;
import com.yahoo.zookeeper.server.quorum.QuorumPacket;
import com.yahoo.zookeeper.server.quorum.Leader.Proposal;
import com.yahoo.zookeeper.txn.CreateSessionTxn;
import com.yahoo.zookeeper.txn.CreateTxn;
import com.yahoo.zookeeper.txn.DeleteTxn;
import com.yahoo.zookeeper.txn.ErrorTxn;
import com.yahoo.zookeeper.txn.SetACLTxn;
import com.yahoo.zookeeper.txn.SetDataTxn;
import com.yahoo.zookeeper.txn.TxnHeader;

/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -> SyncRequestProcessor -> FinalRequestProcessor
 */
public class ZooKeeperServer implements SessionExpirer, ServerStats.Provider {
    /**
     * Create an instance of Zookeeper server
     */
    public interface Factory {
        public ZooKeeperServer createServer() throws IOException;

        public NIOServerCnxn.Factory createConnectionFactory()
                throws IOException;
    }

    /**
     * The server delegates loading of the tree to an instance of the interface
     */
    public interface DataTreeBuilder {
        public DataTree build();
    }

    static public class BasicDataTreeBuilder implements DataTreeBuilder {
        public DataTree build() {
            return new DataTree();
        }
    }

    private static final int DEFAULT_TICK_TIME = 3000;
    protected int tickTime = DEFAULT_TICK_TIME;

    public static final int commitLogCount = 500;
    public int commitLogBuffer = 700;
    public LinkedList<Proposal> committedLog = new LinkedList<Proposal>();
    public long minCommittedLog, maxCommittedLog;
    private DataTreeBuilder treeBuilder;
    public DataTree dataTree;
    protected SessionTracker sessionTracker;
    /**
     * directory for storing the snapshot
     */
    File dataDir;
    /**
     * directoy for storing the log tnxns
     */
    File dataLogDir;
    protected ConcurrentHashMap<Long, Integer> sessionsWithTimeouts;
    protected long hzxid = 0;
    final public static Exception ok = new Exception("No prob");
    protected RequestProcessor firstProcessor;
    LinkedBlockingQueue<Long> sessionsToDie = new LinkedBlockingQueue<Long>();
    protected boolean running;
    /**
     * This is the secret that we use to generate passwords, for the moment it
     * is more of a sanity check.
     */
    final private long superSecret = 0XB3415C00L;
    int requestsInProcess;
    ArrayList<ChangeRecord> outstandingChanges = new ArrayList<ChangeRecord>();
    private NIOServerCnxn.Factory serverCnxnFactory;

    /*
     * Start up the ZooKeeper server.
     * 
     * @param args the port and data directory
     */
    public static void main(String[] args) {
        ServerConfig.parse(args);
        runStandalone(new Factory() {
            public NIOServerCnxn.Factory createConnectionFactory()
                    throws IOException {
                return new NIOServerCnxn.Factory(ServerConfig.getClientPort());
            }

            public ZooKeeperServer createServer() throws IOException {
                return new ZooKeeperServer(new BasicDataTreeBuilder());
            }
        });
    }

    public static void runStandalone(Factory factory) {
        try {
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            ServerStats.registerAsConcrete();
            ZooKeeperServer zk = factory.createServer();
            zk.startup();
            NIOServerCnxn.Factory t = factory.createConnectionFactory();
            t.setZooKeeperServer(zk);
            t.join();
            if (zk.isRunning())
                zk.shutdown();
        } catch (Exception e) {
            ZooLog.logException(e);
        }
        System.exit(0);
    }

    void removeCnxn(ServerCnxn cnxn) {
        dataTree.removeCnxn(cnxn);
    }

    /**
     * Creates a ZooKeeperServer instance. It sets everything up, but doesn't
     * actually start listening for clients until run() is invoked.
     * 
     * @param dataDir
     *            the directory to put the data
     * @throws IOException
     */
    public ZooKeeperServer(File dataDir, File dataLogDir, int tickTime,
            DataTreeBuilder treeBuilder) throws IOException {
        this.treeBuilder = treeBuilder;
        this.dataDir = dataDir;
        this.dataLogDir = dataLogDir;
        this.tickTime = tickTime;
        if (!dataDir.isDirectory()) {
            throw new IOException("data directory does not exist");
        }
        ServerStats.getInstance().setStatsProvider(this);
    }

    /**
     * This constructor is for backward comaptibility with the existing unit
     * test code.
     */
    public ZooKeeperServer(File dataDir, File dataLogDir, int tickTime)
            throws IOException {
        this.treeBuilder = new BasicDataTreeBuilder();
        this.dataDir = dataDir;
        this.dataLogDir = dataLogDir;
        this.tickTime = tickTime;
        if (!dataDir.isDirectory()) {
            throw new IOException("data directory does not exist");
        }
        ServerStats.getInstance().setStatsProvider(this);
    }

    /**
     * Default constructor, relies on the config for its agrument values
     * 
     * @throws IOException
     */
    public ZooKeeperServer(DataTreeBuilder treeBuilder) throws IOException {
        this(new File(ServerConfig.getDataDir()), new File(ServerConfig
                .getDataLogDir()), DEFAULT_TICK_TIME, treeBuilder);
    }

    public static long getZxidFromName(String name, String prefix) {
        long zxid = -1;
        String nameParts[] = name.split("\\.");
        if (nameParts.length == 2 && nameParts[0].equals(prefix)) {
            try {
                zxid = Long.parseLong(nameParts[1], 16);
            } catch (NumberFormatException e) {
            }
        }
        return zxid;
    }

    static public long isValidSnapshot(File f) throws IOException {
        long zxid = getZxidFromName(f.getName(), "snapshot");
        if (zxid == -1)
            return -1;

        // Check for a valid snapshot
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(raf.length() - 5);
            byte bytes[] = new byte[5];
            raf.read(bytes);
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            int len = bb.getInt();
            byte b = bb.get();
            if (len != 1 || b != '/') {
                ZooLog.logWarn("Invalid snapshot " + f + " len = " + len
                        + " byte = " + (b & 0xff));
                return -1;
            }
        } finally {
            raf.close();
        }

        return zxid;
    }

    static File[] getLogFiles(File logDir, long snapshotZxid) {
        List<File> files = Arrays.asList(logDir.listFiles());
        Collections.sort(files, new Comparator<File>() {
            public int compare(File o1, File o2) {
                long z1 = getZxidFromName(o1.getName(), "log");
                long z2 = getZxidFromName(o2.getName(), "log");
                return z1 < z2 ? -1 : (z1 > z2 ? 1 : 0);
            }
        });
        // Find the log file that starts before or at the same time as the
        // zxid of the snapshot
        long logZxid = 0;
        for (File f : files) {
            long fzxid = getZxidFromName(f.getName(), "log");
            if (fzxid > snapshotZxid) {
                continue;
            }
            if (fzxid > logZxid) {
                logZxid = fzxid;
            }
        }
        List<File> v = new ArrayList<File>(5);
        // Apply the logs
        for (File f : files) {
            long fzxid = getZxidFromName(f.getName(), "log");
            if (fzxid < logZxid) {
                continue;
            }
            v.add(f);
        }
        return v.toArray(new File[0]);
    }

    public void loadData() throws IOException, FileNotFoundException,
            SyncFailedException, InterruptedException {
        long highestZxid = 0;
        for (File f : dataDir.listFiles()) {
            long zxid = isValidSnapshot(f);
            if (zxid == -1) {
                ZooLog.logWarn("Skipping " + f);
                continue;
            }
            if (zxid > highestZxid) {
                highestZxid = zxid;
            }
        }
        // Restore sessions and data
        // Find the latest snapshot
        File snapshot = new File(dataDir, "snapshot."
                + Long.toHexString(highestZxid));
        if (snapshot.exists()) {
            long snapShotZxid = highestZxid;
            ZooLog.logWarn("Processing snapshot: " + snapshot);
            InputStream snapIS =
                new BufferedInputStream(new FileInputStream(snapshot));
            loadData(BinaryInputArchive.getArchive(snapIS));
            snapIS.close();            
            dataTree.lastProcessedZxid = highestZxid;
            File[] files = getLogFiles(dataLogDir, snapShotZxid);
            // Apply the logs
            for (File f : files) {
                ZooLog.logWarn("Processing log file: " + f);
                InputStream logIS =
                    new BufferedInputStream(new FileInputStream(f));
                highestZxid = playLog(BinaryInputArchive.getArchive(logIS));
                logIS.close();                
            }
            hzxid = highestZxid;
            ZooLog.logWarn("Snapshot/log data restored");
        } else {
            sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
            dataTree = treeBuilder.build();
        }
        // Clean up dead sessions
        LinkedList<Long> deadSessions = new LinkedList<Long>();
        for (long session : dataTree.getSessions()) {
            if (sessionsWithTimeouts.get(session) == null) {
                deadSessions.add(session);
            }
        }
        dataTree.initialized = true;
        for (long session : deadSessions) {
            killSession(session);
        }
        // Make a clean snapshot
        snapshot();
    }

    public void loadData(InputArchive ia) throws IOException {
        sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
        dataTree = treeBuilder.build();

        int count = ia.readInt("count");
        while (count > 0) {
            long id = ia.readLong("id");
            int to = ia.readInt("timeout");
            sessionsWithTimeouts.put(id, to);
            ZooLog.logTextTraceMessage("loadData --- session in archive: " + id
                    + " with timeout: " + to, ZooLog.SESSION_TRACE_MASK);
            count--;
        }
        dataTree.deserialize(ia, "tree");
    }

    public long playLog(InputArchive logStream) throws IOException {
        long highestZxid = 0;
        try {
            while (true) {
                byte[] bytes = logStream.readBuffer("txnEntry");
                if (bytes.length == 0) {
                    // Since we preallocate, we define EOF to be an
                    // empty transaction
                    throw new EOFException();
                }
                InputArchive ia = BinaryInputArchive
                        .getArchive(new ByteArrayInputStream(bytes));
                TxnHeader hdr = new TxnHeader();
                Record txn = deserializeTxn(ia, hdr);
                if (logStream.readByte("EOR") != 'B') {
                    ZooLog.logError("Last transaction was partial.");
                    throw new EOFException();
                }
                if (hdr.getZxid() <= highestZxid && highestZxid != 0) {
                    ZooLog.logError(highestZxid + "(higestZxid) >= "
                            + hdr.getZxid() + "(next log) for type "
                            + hdr.getType());
                } else {
                    highestZxid = hdr.getZxid();
                }
                switch (hdr.getType()) {
                case OpCode.createSession:
                    sessionsWithTimeouts.put(hdr.getClientId(),
                            ((CreateSessionTxn) txn).getTimeOut());
                    ZooLog.logTextTraceMessage(
                            "playLog --- create session in log: "
                                    + Long.toHexString(hdr.getClientId())
                                    + " with timeout: "
                                    + ((CreateSessionTxn) txn).getTimeOut(),
                            ZooLog.SESSION_TRACE_MASK);
                    // give dataTree a chance to sync its lastProcessedZxid
                    dataTree.processTxn(hdr, txn);
                    break;
                case OpCode.closeSession:
                    sessionsWithTimeouts.remove(hdr.getClientId());
                    ZooLog.logTextTraceMessage(
                            "playLog --- close session in log: "
                                    + Long.toHexString(hdr.getClientId()),
                            ZooLog.SESSION_TRACE_MASK);
                    dataTree.processTxn(hdr, txn);
                    break;
                default:
                    dataTree.processTxn(hdr, txn);
                }
                Request r = new Request(null, 0, hdr.getCxid(), hdr.getType(),
                        null, null);
                r.txn = txn;
                r.hdr = hdr;
                r.zxid = hdr.getZxid();
                addCommittedProposal(r);
            }
        } catch (EOFException e) {
        }
        return highestZxid;
    }

    /**
     * maintains a list of last 500 or so committed requests. This is used for
     * fast follower synchronization.
     * 
     * @param r
     *            committed request
     */

    public void addCommittedProposal(Request request) {
        synchronized (committedLog) {
            if (committedLog.size() > commitLogCount) {
                committedLog.removeFirst();
                minCommittedLog = committedLog.getFirst().packet.getZxid();
            }
            if (committedLog.size() == 0) {
                minCommittedLog = request.zxid;
                maxCommittedLog = request.zxid;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            try {
                request.hdr.serialize(boa, "hdr");
                if (request.txn != null) {
                    request.txn.serialize(boa, "txn");
                }
                baos.close();
            } catch (IOException e) {
                // This really should be impossible
                ZooLog.logException(e);
            }
            QuorumPacket pp = new QuorumPacket(Leader.PROPOSAL, request.zxid,
                    baos.toByteArray(), null);
            Proposal p = new Proposal();
            p.packet = pp;
            p.request = request;
            committedLog.add(p);
            maxCommittedLog = p.packet.getZxid();
        }
    }

    static public Record deserializeTxn(InputArchive ia, TxnHeader hdr)
            throws IOException {
        hdr.deserialize(ia, "hdr");
        Record txn = null;
        switch (hdr.getType()) {
        case OpCode.createSession:
            // This isn't really an error txn; it just has the same
            // format. The error represents the timeout
            txn = new CreateSessionTxn();
            break;
        case OpCode.closeSession:
            return null;
        case OpCode.create:
            txn = new CreateTxn();
            break;
        case OpCode.delete:
            txn = new DeleteTxn();
            break;
        case OpCode.setData:
            txn = new SetDataTxn();
            break;
        case OpCode.setACL:
            txn = new SetACLTxn();
            break;
        case OpCode.error:
            txn = new ErrorTxn();
            break;
        }
        if (txn != null) {
            txn.deserialize(ia, "txn");
        }
        return txn;
    }

    public void truncateLog(long finalZxid) throws IOException {
        long highestZxid = 0;
        for (File f : dataDir.listFiles()) {
            long zxid = isValidSnapshot(f);
            if (zxid == -1) {
                ZooLog.logWarn("Skipping " + f);
                continue;
            }
            if (zxid > highestZxid) {
                highestZxid = zxid;
            }
        }
        File[] files = getLogFiles(dataLogDir, highestZxid);
        boolean truncated = false;
        for (File f : files) {
            FileInputStream fin = new FileInputStream(f);
            InputArchive ia = BinaryInputArchive.getArchive(fin);
            FileChannel fchan = fin.getChannel();
            try {
                while (true) {
                    byte[] bytes = ia.readBuffer("txtEntry");
                    if (bytes.length == 0) {
                        throw new EOFException();
                    }
                    InputArchive iab = BinaryInputArchive
                            .getArchive(new ByteArrayInputStream(bytes));
                    TxnHeader hdr = new TxnHeader();
                    deserializeTxn(iab, hdr);
                    if (ia.readByte("EOF") != 'B') {
                        throw new EOFException();
                    }
                    if (hdr.getZxid() == finalZxid) {
                        // this is where we need to truncate

                        long pos = fchan.position();
                        fin.close();
                        FileOutputStream fout = new FileOutputStream(f);
                        FileChannel fchanOut = fout.getChannel();
                        fchanOut.truncate(pos);
                        truncated = true;
                        break;
                    }
                }
            } catch (EOFException eof) {
            }
            if (truncated == true) {
                break;
            }
        }
        if (truncated == false) {
            // not able to truncate the log
            ZooLog.logError("Not able to truncate the log "
                    + Long.toHexString(finalZxid));
            System.exit(13);
        }

    }

    public void snapshot(BinaryOutputArchive oa) throws IOException,
            InterruptedException {
        HashMap<Long, Integer> sessSnap = new HashMap<Long, Integer>(
                sessionsWithTimeouts);
        oa.writeInt(sessSnap.size(), "count");
        for (Entry<Long, Integer> entry : sessSnap.entrySet()) {
            oa.writeLong(entry.getKey().longValue(), "id");
            oa.writeInt(entry.getValue().intValue(), "timeout");
        }
        dataTree.serialize(oa, "tree");
    }

    public void snapshot() throws InterruptedException {
        long lastZxid = dataTree.lastProcessedZxid;
        ZooLog.logTextTraceMessage(
                "Snapshotting: " + Long.toHexString(lastZxid),
                ZooLog.textTraceMask);
        try {
            File f =new File(dataDir, "snapshot." + Long.toHexString(lastZxid));
            OutputStream sessOS = new BufferedOutputStream(new FileOutputStream(f));
            BinaryOutputArchive oa = BinaryOutputArchive.getArchive(sessOS);
            snapshot(oa);
            sessOS.flush();
            sessOS.close();
            ZooLog.logTextTraceMessage(
                    "Snapshotting finished: " + Long.toHexString(lastZxid),
                    ZooLog.textTraceMask);
        } catch (IOException e) {
            ZooLog.logException(e, "Severe error, exiting");
            // This is a severe error that we cannot recover from,
            // so we need to exit
            System.exit(10);
        }
    }

    /**
     * This should be called from a synchronized block on this!
     */
    public long getZxid() {
        return hzxid;
    }

    synchronized long getNextZxid() {
        return ++hzxid;
    }

    long getTime() {
        return System.currentTimeMillis();
    }

    static String getLogName(long zxid) {
        return "log." + Long.toHexString(zxid);
    }

    public void closeSession(long sessionId) throws KeeperException,
            InterruptedException {
        ZooLog.logTextTraceMessage("ZooKeeperServer --- Session to be closed: "
                + Long.toHexString(sessionId), ZooLog.SESSION_TRACE_MASK);
        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        submitRequest(null, sessionId, OpCode.closeSession, 0, null, null);
    }

    protected void killSession(long sessionId) {
        dataTree.killSession(sessionId);
        ZooLog.logTextTraceMessage("ZooKeeperServer --- killSession: "
                + Long.toHexString(sessionId), ZooLog.SESSION_TRACE_MASK);
        if (sessionTracker != null) {
            sessionTracker.removeSession(sessionId);
        }
    }

    public void expire(long sessionId) {
        try {
            ZooLog.logTextTraceMessage(
                    "ZooKeeperServer --- Session to expire: " + Long.toHexString(sessionId),
                    ZooLog.SESSION_TRACE_MASK);
            closeSession(sessionId);
        } catch (Exception e) {
            ZooLog.logException(e);
        }
    }

    void touch(ServerCnxn cnxn) throws IOException {
        if (cnxn == null) {
            return;
        }
        long id = cnxn.getSessionId();
        int to = cnxn.getSessionTimeout();
        if (!sessionTracker.touchSession(id, to)) {
            throw new IOException("Missing session " + Long.toHexString(id));
        }
    }

    public void startup() throws IOException, InterruptedException {
        if (dataTree == null) {
            loadData();
        }
        createSessionTracker();
        setupRequestProcessors();
        running = true;
        synchronized (this) {
            notifyAll();
        }
    }

    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor syncProcessor = new SyncRequestProcessor(this,
                finalProcessor);
        firstProcessor = new PrepRequestProcessor(this, syncProcessor);
    }

    protected void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, sessionsWithTimeouts,
                tickTime, 1);
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        // new RuntimeException("Calling shutdown").printStackTrace();
        this.running = false;
        // Since sessionTracker and syncThreads poll we just have to
        // set running to false and they will detect it during the poll
        // interval.
        if (sessionTracker != null) {
            sessionTracker.shutdown();
        }
        if (firstProcessor != null) {
            firstProcessor.shutdown();
        }
        if (dataTree != null) {
            dataTree.clear();
        }
    }

    synchronized public void incInProcess() {
        requestsInProcess++;
    }

    synchronized public void decInProcess() {
        requestsInProcess--;
    }

    public int getInProcess() {
        return requestsInProcess;
    }

    /**
     * This structure is used to facilitate information sharing between PrepRP
     * and FinalRP.
     */
    static class ChangeRecord {
        ChangeRecord(long zxid, String path, Stat stat, int childCount,
                ArrayList<ACL> acl) {
            this.zxid = zxid;
            this.path = path;
            this.stat = stat;
            this.childCount = childCount;
            this.acl = acl;
        }

        long zxid;

        String path;

        Stat stat; /* Make sure to create a new object when changing */

        int childCount;

        ArrayList<ACL> acl; /* Make sure to create a new object when changing */

        @SuppressWarnings("unchecked")
        ChangeRecord duplicate(long zxid) {
            Stat stat = new Stat();
            if (this.stat != null) {
                DataTree.copyStat(this.stat, stat);
            }
            return new ChangeRecord(zxid, path, stat, childCount,
                    acl == null ? new ArrayList<ACL>() : (ArrayList<ACL>) acl
                            .clone());
        }
    }

    byte[] generatePasswd(long id) {
        Random r = new Random(id ^ superSecret);
        byte p[] = new byte[16];
        r.nextBytes(p);
        return p;
    }

    protected boolean checkPasswd(long sessionId, byte[] passwd) {
        return sessionId != 0
                && Arrays.equals(passwd, generatePasswd(sessionId));
    }

    long createSession(ServerCnxn cnxn, byte passwd[], int timeout)
            throws InterruptedException {
        long sessionId = sessionTracker.createSession(timeout);
        Random r = new Random(sessionId ^ superSecret);
        r.nextBytes(passwd);
        ByteBuffer to = ByteBuffer.allocate(4);
        to.putInt(timeout);
        cnxn.setSessionId(sessionId);
        submitRequest(cnxn, sessionId, OpCode.createSession, 0, to, null);
        return sessionId;
    }

    protected void revalidateSession(ServerCnxn cnxn, long sessionId,
            int sessionTimeout) throws IOException, InterruptedException {
        boolean rc = sessionTracker.touchSession(sessionId, sessionTimeout);
        ZooLog.logTextTraceMessage("Session " + Long.toHexString(sessionId) + 
                " is valid: " + rc,ZooLog.SESSION_TRACE_MASK);
        cnxn.finishSessionInit(rc);
    }

    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
            int sessionTimeout) throws IOException, InterruptedException {
        if (!checkPasswd(sessionId, passwd)) {
            cnxn.finishSessionInit(false);
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    public void closeSession(ServerCnxn cnxn, RequestHeader requestHeader)
            throws KeeperException, InterruptedException {
        closeSession(cnxn.getSessionId());
    }

    public long getServerId() {
        return 0;
    }

    /**
     * @param cnxn
     * @param sessionId
     * @param xid
     * @param bb
     */
    public void submitRequest(ServerCnxn cnxn, long sessionId, int type,
            int xid, ByteBuffer bb, ArrayList<Id> authInfo) {
        if (firstProcessor == null) {
            synchronized (this) {
                try {
                    while (!running) {
                        wait(1000);
                    }
                } catch (InterruptedException e) {
                    ZooLog.logException(e);
                }
                if (firstProcessor == null) {
                    throw new RuntimeException("Not started");
                }
            }
        }
        try {
            touch(cnxn);
            Request si = new Request(cnxn, sessionId, xid, type, bb, authInfo);
            boolean validpacket = Request.isValid(type);
            if (validpacket) {
                firstProcessor.processRequest(si);
                if (cnxn != null) {
                    incInProcess();
                }
            } else {
                ZooLog.logWarn("Dropping packet at server of type " + type);
                // if unvalid packet drop the packet.
            }
        } catch (IOException e) {
            ZooLog.logException(e);
        }
    }

    static public void byteBuffer2Record(ByteBuffer bb, Record record)
            throws IOException {
        BinaryInputArchive ia;
        ia = BinaryInputArchive.getArchive(new ByteBufferInputStream(bb));
        record.deserialize(ia, "request");
    }

    public static int getSnapCount() {
        String sc = System.getProperty("zookeeper.snapCount");
        try {
            return Integer.parseInt(sc);
        } catch (Exception e) {
            return 10000;
        }
    }

    public int getGlobalOutstandingLimit() {
        String sc = System.getProperty("zookeeper.globalOutstandingLimit");
        int limit;
        try {
            limit = Integer.parseInt(sc);
        } catch (Exception e) {
            limit = 1000;
        }
        return limit;
    }

    public void setServerCnxnFactory(NIOServerCnxn.Factory factory) {
        serverCnxnFactory = factory;
    }

    public NIOServerCnxn.Factory getServerCnxnFactory() {
        return serverCnxnFactory;
    }

    public long getLastProcessedZxid() {
        return dataTree.lastProcessedZxid;
    }

    public long getOutstandingRequests() {
        return getInProcess();
    }
}
