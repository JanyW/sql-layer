/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.pg;

import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.store.Store;
import com.akiban.sql.StandardException;
import com.akiban.sql.parser.SQLParser;
import com.akiban.sql.parser.StatementNode;
import com.akiban.sql.parser.ParameterNode;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.qp.persistitadapter.OperatorStore;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.physicaloperator.StoreAdapter;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.error.ErrorCode;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.NoTransactionInProgressException;
import com.akiban.server.error.ParseException;
import com.akiban.server.error.PersistItErrorException;
import com.akiban.server.error.TransactionInProgressException;
import com.akiban.server.error.UnsupportedSQLException;
import com.akiban.server.service.instrumentation.SessionTracer;
import com.akiban.server.service.EventTypes;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.PersistitStore;

import com.persistit.Transaction;
import com.persistit.exception.PersistitException;
import com.persistit.exception.RollbackException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Connection to a Postgres server client.
 * Runs in its own thread; has its own AkServer Session.
 *
 */
public class PostgresServerConnection implements PostgresServerSession, Runnable
{
    private static final Logger logger = LoggerFactory.getLogger(PostgresServerConnection.class);

    private final PostgresServer server;
    private final PostgresServiceRequirements reqs;
    private boolean running = false, ignoreUntilSync = false;
    private Socket socket;
    private PostgresMessenger messenger;
    private int pid, secret;
    private int version;
    private Properties properties;
    private Map<String,Object> attributes = new HashMap<String,Object>();
    private Map<String,PostgresStatement> preparedStatements =
        new HashMap<String,PostgresStatement>();
    private Map<String,PostgresStatement> boundPortals =
        new HashMap<String,PostgresStatement>();

    private Session session;
    private int aisGeneration = -1;
    private AkibanInformationSchema ais;
    private StoreAdapter adapter;
    private String defaultSchemaName;
    private SQLParser parser;
    private PostgresStatementCache statementCache;
    private PostgresStatementParser[] unparsedGenerators;
    private PostgresStatementGenerator[] parsedGenerators;
    private Thread thread;
    private Transaction transaction;
    
    private boolean instrumentationEnabled = false;
    private String sql;
    private PostgresSessionTracer sessionTracer;

    public PostgresServerConnection(PostgresServer server, Socket socket, 
                                    int pid, int secret,
                                    PostgresServiceRequirements reqs
    ) {
        this.server = server;
        this.reqs = reqs;

        this.socket = socket;
        this.pid = pid;
        this.secret = secret;
        this.sessionTracer = new PostgresSessionTracer(pid, server.isInstrumentationEnabled());
        sessionTracer.setRemoteAddress(socket.getInetAddress().getHostAddress());
    }

    public void start() {
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        running = false;
        // Can only wake up stream read by closing down socket.
        try {
            socket.close();
        }
        catch (IOException ex) {
        }
        if ((thread != null) && (thread != Thread.currentThread())) {
            try {
                // Wait a bit, but don't hang up shutdown if thread is wedged.
                thread.join(500);
                if (thread.isAlive())
                    logger.warn("Connection " + pid + " still running.");
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    public void run() {
        try {
            // We flush() when we mean it. 
            // So, turn off kernel delay, but wrap a buffer so every
            // message isn't its own packet.
            socket.setTcpNoDelay(true);
            messenger = new PostgresMessenger(socket.getInputStream(),
                                              new BufferedOutputStream(socket.getOutputStream()));
            topLevel();
        }
        catch (Exception ex) {
            if (running)
                logger.warn("Error in server", ex);
        }
        finally {
            try {
                socket.close();
            }
            catch (IOException ex) {
            }
        }
    }

    //protected enum ErrorMode { NONE, SIMPLE, EXTENDED };

    protected void topLevel() throws IOException, Exception {
        logger.info("Connect from {}" + socket.getRemoteSocketAddress());
        boolean startupComplete = false;
        try {
            while (running) {
                PostgresMessages type = messenger.readMessage(startupComplete);
                if (ignoreUntilSync) {
                    if ((type != PostgresMessages.EOF_TYPE) && (type != PostgresMessages.SYNC_TYPE))
                        continue;
                    ignoreUntilSync = false;
                }
                try {
                    sessionTracer.beginEvent(EventTypes.PROCESS);
                    switch (type) {
                    case EOF_TYPE: // EOF
                        stop();
                        break;
                    case SYNC_TYPE:
                        readyForQuery();
                        break;
                    case STARTUP_MESSAGE_TYPE:
                        startupComplete = processStartupMessage();
                        break;
                    case PASSWORD_MESSAGE_TYPE:
                        processPasswordMessage();
                        break;
                    case QUERY_TYPE:
                        processQuery();
                        break;
                    case PARSE_TYPE:
                        processParse();
                        break;
                    case BIND_TYPE:
                        processBind();
                        break;
                    case DESCRIBE_TYPE:
                        processDescribe();
                        break;
                    case EXECUTE_TYPE:
                        processExecute();
                        break;
                    case CLOSE_TYPE:
                        processClose();
                        break;
                    case TERMINATE_TYPE:
                        processTerminate();
                        break;
                    }
                }
                catch (InvalidOperationException ex) {
                    logger.warn("Error in query: {}",ex.getMessage());
                    logger.debug("StackTrace: {}", ex);
                    if (type.errorMode() == PostgresMessages.ErrorMode.NONE ) throw ex;
                    else {
                        messenger.beginMessage(PostgresMessages.ERROR_RESPONSE_TYPE.code());
                        messenger.write('S');
                        messenger.writeString("ERROR");
                        messenger.write('C');
                        messenger.writeString(ex.getCode().getFormattedValue());
                        messenger.write('M');
                        messenger.writeString(ex.getShortMessage());
                        messenger.write(0);
                        messenger.sendMessage(true);
                    }
                    if (type.errorMode() == PostgresMessages.ErrorMode.EXTENDED)
                        ignoreUntilSync = true;
                    else
                        readyForQuery();
                } catch (Exception e) {
                    final String message = (e.getMessage() == null ? e.getClass().toString() : e.getMessage()); 
                    logger.warn("Unexpected error in query", e);
                    logger.debug("Stack Trace: {}", e);
                    if (type.errorMode() == PostgresMessages.ErrorMode.NONE) throw e;
                    else {
                        messenger.beginMessage(PostgresMessages.ERROR_RESPONSE_TYPE.code());
                        messenger.write('S');
                        messenger.writeString("ERROR");
                        messenger.write('C');
                        messenger.writeString(ErrorCode.UNEXPECTED_EXCEPTION.getFormattedValue());
                        messenger.write('M');
                        messenger.writeString(message);
                        messenger.write(0);
                        messenger.sendMessage(true);
                    }
                    if (type.errorMode() == PostgresMessages.ErrorMode.EXTENDED)
                        ignoreUntilSync = true;
                    else
                        readyForQuery();
                }
                finally {
                    sessionTracer.endEvent();
                }
            }
        }
        finally {
            if (transaction != null) {
                transaction.end();
                transaction = null;
            }
            server.removeConnection(pid);
        }
    }

    protected void readyForQuery() throws IOException {
        messenger.beginMessage(PostgresMessages.READY_FOR_QUERY_TYPE.code());
        messenger.writeByte('I'); // Idle ('T' -> xact open; 'E' -> xact abort)
        messenger.sendMessage(true);
    }

    protected boolean processStartupMessage() throws IOException {
        int version = messenger.readInt();
        switch (version) {
        case PostgresMessenger.VERSION_CANCEL:
            processCancelRequest();
            return false;
        case PostgresMessenger.VERSION_SSL:
            processSSLMessage();
            return false;
        default:
            this.version = version;
            logger.debug("Version {}.{}", (version >> 16), (version & 0xFFFF));
        }

        properties = new Properties();
        while (true) {
            String param = messenger.readString();
            if (param.length() == 0) break;
            String value = messenger.readString();
            properties.put(param, value);
        }
        logger.debug("Properties: {}", properties);
        String enc = properties.getProperty("client_encoding");
        if (enc != null) {
            if ("UNICODE".equals(enc))
                messenger.setEncoding("UTF-8");
            else
                messenger.setEncoding(enc);
        }

        // Get initial version of AIS.
        session = reqs.sessionService().createSession();
        updateAIS();

        {
            messenger.beginMessage(PostgresMessages.AUTHENTICATION_TYPE.code());
            messenger.writeInt(PostgresMessenger.AUTHENTICATION_CLEAR_TEXT);
            messenger.sendMessage(true);
        }
        return true;
    }

    protected void processCancelRequest() throws IOException {
        int pid = messenger.readInt();
        int secret = messenger.readInt();
        PostgresServerConnection connection = server.getConnection(pid);
        if ((connection != null) && (secret == connection.secret))
            // No easy way to signal in another thread.
            connection.messenger.setCancel(true);
        stop();                                         // That's all for this connection.
    }

    protected void processSSLMessage() throws IOException {
        OutputStream raw = messenger.getOutputStream();
        raw.write('N');         // No SSL support.
        raw.flush();
    }

    protected void processPasswordMessage() throws IOException {
        String user = properties.getProperty("user");
        String pass = messenger.readString();
        logger.info("Login {}/{}", user, pass);
        Properties status = new Properties();
        // This is enough to make the JDBC driver happy.
        status.put("client_encoding", properties.getProperty("client_encoding", "UNICODE"));
        status.put("server_encoding", messenger.getEncoding());
        status.put("server_version", "8.4.7"); // Not sure what the min it'll accept is.
        status.put("session_authorization", user);
        
        {
            messenger.beginMessage(PostgresMessages.AUTHENTICATION_TYPE.code());
            messenger.writeInt(PostgresMessenger.AUTHENTICATION_OK);
            messenger.sendMessage();
        }
        for (String prop : status.stringPropertyNames()) {
            messenger.beginMessage(PostgresMessages.PARAMETER_STATUS_TYPE.code());
            messenger.writeString(prop);
            messenger.writeString(status.getProperty(prop));
            messenger.sendMessage();
        }
        {
            messenger.beginMessage(PostgresMessages.BACKEND_KEY_DATA_TYPE.code());
            messenger.writeInt(pid);
            messenger.writeInt(secret);
            messenger.sendMessage();
        }
        readyForQuery();
    }

    protected void processQuery() throws IOException {
        long startTime = System.nanoTime();
        int rowsProcessed = 0;
        sql = messenger.readString();
        sessionTracer.setCurrentStatement(sql);
        logger.info("Query: {}", sql);

        updateAIS();

        PostgresStatement pstmt = null;
        if (statementCache != null)
            pstmt = statementCache.get(sql);
        if (pstmt == null) {
            for (PostgresStatementParser parser : unparsedGenerators) {
                // Try special recognition first; only allowed to turn
                // into one statement.
                pstmt = parser.parse(this, sql, null);
                if (pstmt != null)
                    break;
            }
        }
        if (pstmt != null) {
            pstmt.sendDescription(this, false);
            try {
                sessionTracer.beginEvent(EventTypes.EXECUTE);
                rowsProcessed = pstmt.execute(this, -1);
            }
            finally {
                sessionTracer.endEvent();
            }
        }
        else {
            // Parse as a _list_ of statements and process each in turn.
            List<StatementNode> stmts;
            try {
                sessionTracer.beginEvent(EventTypes.PARSE);
                stmts = parser.parseStatements(sql);
            } catch (StandardException ex) {
                throw new ParseException ("", ex.getMessage(), sql);
            }
            finally {
                sessionTracer.endEvent();
            }
            for (StatementNode stmt : stmts) {
                pstmt = generateStatement(stmt, null, null);
                if ((statementCache != null) && (stmts.size() == 1))
                    statementCache.put(sql, pstmt);
                pstmt.sendDescription(this, false);
                try {
                    sessionTracer.beginEvent(EventTypes.EXECUTE);
                    rowsProcessed = pstmt.execute(this, -1);
                }
                finally {
                    sessionTracer.endEvent();
                }
            }
        }
        readyForQuery();
        logger.debug("Query complete");
        if (reqs.instrumentation().isQueryLogEnabled()) {
            reqs.instrumentation().logQuery(pid, sql, (System.nanoTime() - startTime), rowsProcessed);
        }
    }

    protected void processParse() throws IOException {
        String stmtName = messenger.readString();
        sql = messenger.readString();
        short nparams = messenger.readShort();
        int[] paramTypes = new int[nparams];
        for (int i = 0; i < nparams; i++)
            paramTypes[i] = messenger.readInt();
        logger.info("Parse: {}", sql);

        updateAIS();

        PostgresStatement pstmt = null;
        if (statementCache != null)
            pstmt = statementCache.get(sql);
        if (pstmt == null) {
            StatementNode stmt;
            List<ParameterNode> params;
            try {
                sessionTracer.beginEvent(EventTypes.PARSE);
                stmt = parser.parseStatement(sql);
                params = parser.getParameterList();
            } catch (StandardException ex) {
                throw new ParseException ("", ex.getMessage(), sql);
            }
            finally {
                sessionTracer.endEvent();
            }
            pstmt = generateStatement(stmt, params, paramTypes);
            if (statementCache != null)
                statementCache.put(sql, pstmt);
        }
        preparedStatements.put(stmtName, pstmt);
        messenger.beginMessage(PostgresMessages.PARSE_COMPLETE_TYPE.code());
        messenger.sendMessage();
    }

    protected void processBind() throws IOException {
        String portalName = messenger.readString();
        String stmtName = messenger.readString();
        String[] params = null;
        {
            boolean[] paramsBinary = null;
            short nformats = messenger.readShort();
            if (nformats > 0) {
                paramsBinary = new boolean[nformats];
                for (int i = 0; i < nformats; i++)
                    paramsBinary[i] = (messenger.readShort() == 1);
            }
            short nparams = messenger.readShort();
            if (nparams > 0) {
                params = new String[nparams];
                boolean binary = false;
                for (int i = 0; i < nparams; i++) {
                    if (i < nformats)
                        binary = paramsBinary[i];
                    int len = messenger.readInt();
                    if (len < 0) continue;      // Null
                    byte[] param = new byte[len];
                    messenger.readFully(param, 0, len);
                    if (binary) {
                        throw new IOException("Don't know how to parse binary format.");
                    }
                    else {
                        params[i] = new String(param, messenger.getEncoding());
                    }
                }
            }
        }
        boolean[] resultsBinary = null; 
        boolean defaultResultsBinary = false;
        {        
            short nresults = messenger.readShort();
            if (nresults == 1)
                defaultResultsBinary = (messenger.readShort() == 1);
            else if (nresults > 0) {
                resultsBinary = new boolean[nresults];
                for (int i = 0; i < nresults; i++) {
                    resultsBinary[i] = (messenger.readShort() == 1);
                }
                defaultResultsBinary = resultsBinary[nresults-1];
            }
        }
        PostgresStatement pstmt = preparedStatements.get(stmtName);
        boundPortals.put(portalName, 
                         pstmt.getBoundStatement(params, 
                                                 resultsBinary, defaultResultsBinary));
        messenger.beginMessage(PostgresMessages.BIND_COMPLETE_TYPE.code());
        messenger.sendMessage();
    }

    protected void processDescribe() throws IOException{
        byte source = messenger.readByte();
        String name = messenger.readString();
        PostgresStatement pstmt;        
        switch (source) {
        case (byte)'S':
            pstmt = preparedStatements.get(name);
            break;
        case (byte)'P':
            pstmt = boundPortals.get(name);
            break;
        default:
            throw new IOException("Unknown describe source: " + (char)source);
        }
        pstmt.sendDescription(this, true);
    }

    protected void processExecute() throws IOException {
        long startTime = System.nanoTime();
        int rowsProcessed = 0;
        String portalName = messenger.readString();
        int maxrows = messenger.readInt();
        PostgresStatement pstmt = boundPortals.get(portalName);
        logger.info("Execute: {}", pstmt.toString());
        try {
            sessionTracer.beginEvent(EventTypes.EXECUTE);
            rowsProcessed = pstmt.execute(this, maxrows);
        }
        finally {
            sessionTracer.endEvent();
        }
        logger.debug("Execute complete");
        if (reqs.instrumentation().isQueryLogEnabled()) {
            reqs.instrumentation().logQuery(pid, sql, (System.nanoTime() - startTime), rowsProcessed);
        }
    }

    protected void processClose() throws IOException {
        byte source = messenger.readByte();
        String name = messenger.readString();
        PostgresStatement pstmt;        
        switch (source) {
        case (byte)'S':
            pstmt = preparedStatements.remove(name);
            break;
        case (byte)'P':
            pstmt = boundPortals.remove(name);
            break;
        default:
            throw new IOException("Unknown describe source: " + (char)source);
        }
        messenger.beginMessage(PostgresMessages.CLOSE_COMPLETE_TYPE.code());
        messenger.sendMessage();
    }
    
    protected void processTerminate() throws IOException {
        stop();
    }

    // When the AIS changes, throw everything away, since it might
    // point to obsolete objects.
    protected void updateAIS() {
        DDLFunctions ddl = reqs.dxl().ddlFunctions();
        // TODO: This could be more reliable if the AIS object itself
        // also knew its generation. Right now, can get new generation
        // # and old AIS and not notice until next change.
        int currentGeneration = ddl.getGeneration();
        if (aisGeneration == currentGeneration) 
            return;             // Unchanged.
        aisGeneration = currentGeneration;
        ais = ddl.getAIS(session);

        parser = new SQLParser();

        defaultSchemaName = getProperty("database");
        // Temporary until completely removed.
        // TODO: Any way / need to ask AIS if schema exists and report error?

        PostgresStatementGenerator compiler, explainer;
        {
            Schema schema;
            // TODO: Temporary choice of optimizer.
            // There is an "options" property that psql allows in the
            // connect string, but no way to pass it to the JDBC
            // driver. So have to use what's available.
            if (!"new-optimizer".equals(properties.getProperty("user"))) {
                PostgresOperatorCompiler oc = new PostgresOperatorCompiler(this);
                schema = oc.getSchema();
                compiler = oc;
                explainer = new PostgresExplainStatementGenerator(this);
            }
            else {
                logger.info("Using new optimizer!");
                PostgresOperatorCompiler_New nc = new PostgresOperatorCompiler_New(this);
                schema = nc.getSchema();
                compiler = nc;
                explainer = new PostgresExplainStatementGenerator_New(this);
            }
            final Store store = reqs.store();
            final PersistitStore persistitStore;
            if (store instanceof OperatorStore)
                persistitStore = ((OperatorStore)store).getPersistitStore();
            else
                persistitStore = (PersistitStore)store;
            adapter = new PersistitAdapter(schema,
                                           persistitStore,
                                           reqs.treeService(),
                                           session);
        }

        statementCache = server.getStatementCache(aisGeneration);
        unparsedGenerators = new PostgresStatementParser[] {
            new PostgresEmulatedMetaDataStatementParser(this)
        };
        parsedGenerators = new PostgresStatementGenerator[] {
            // Can be ordered by frequency so long as there is no overlap.
            compiler,
            new PostgresDDLStatementGenerator(this),
            new PostgresSessionStatementGenerator(this),
            explainer
        };
    }

    protected void sessionChanged() {
        if (parsedGenerators == null) return; // setAttribute() from generator's ctor.
        for (PostgresStatementParser parser : unparsedGenerators) {
            parser.sessionChanged(this);
        }
        for (PostgresStatementGenerator generator : parsedGenerators) {
            generator.sessionChanged(this);
        }
    }

    protected PostgresStatement generateStatement(StatementNode stmt, 
                                                  List<ParameterNode> params,
                                                  int[] paramTypes) {
        try {
            sessionTracer.beginEvent(EventTypes.OPTIMIZE);
            for (PostgresStatementGenerator generator : parsedGenerators) {
                PostgresStatement pstmt = generator.generate(this, stmt, 
                                                             params, paramTypes);
                if (pstmt != null) return pstmt;
            }
        }
        finally {
            sessionTracer.endEvent();
        }
        throw new UnsupportedSQLException ("", stmt);
    }

    /* PostgresServerSession */

    @Override
    public PostgresMessenger getMessenger() {
        return messenger;
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    @Override
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    @Override
    public String getProperty(String key, String defval) {
        return properties.getProperty(key, defval);
    }

    @Override
    public Map<String,Object> getAttributes() {
        return attributes;
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object attr) {
        attributes.put(key, attr);
        sessionChanged();
    }

    @Override
    public DXLService getDXL() {
        return reqs.dxl();
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public String getDefaultSchemaName() {
        return defaultSchemaName;
    }

    @Override
    public void setDefaultSchemaName(String defaultSchemaName) {
        this.defaultSchemaName = defaultSchemaName;
        sessionChanged();
    }

    @Override
    public AkibanInformationSchema getAIS() {
        return ais;
    }

    @Override
    public SQLParser getParser() {
        return parser;
    }
    
    @Override
    public SessionTracer getSessionTracer() {
        return sessionTracer;
     }

    @Override
    public StoreAdapter getStore() {
        return adapter;
    }

    public boolean isInstrumentationEnabled() {
        return instrumentationEnabled;
    }
    
    public void enableInstrumentation() {
        sessionTracer.enable();
        instrumentationEnabled = true;
    }
    
    public void disableInstrumentation() {
        sessionTracer.disable();
        instrumentationEnabled = false;
    }
    
    public String getSqlString() {
        return sql;
    }
    
    public String getRemoteAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    public void beginTransaction() {
        if (transaction != null)
            throw new TransactionInProgressException ();
        transaction = reqs.treeService().getTransaction(session);
        try {
            transaction.begin();
        }
        catch (PersistitException ex) {
            throw new PersistItErrorException (ex);
        }
    }

    public void commitTransaction() {
        if (transaction == null)
            throw new NoTransactionInProgressException();
        try {
            transaction.commit();
        }
        catch (PersistitException ex) {
            throw new PersistItErrorException(ex);
        }
        finally {
            transaction.end();
        }
        transaction = null;
    }

    public void rollbackTransaction() {
        if (transaction == null)
            throw new NoTransactionInProgressException();
        try {
            transaction.rollback();
        }
        catch (PersistitException ex) {
            throw new PersistItErrorException (ex);
        }
        catch (RollbackException ex) {
        }
        finally {
            transaction.end();
        }
        transaction = null;
    }

}
