package org.example.kvstore;

import org.example.kvstore.cmd.*;
import org.example.kvstore.distribution.ConsistentHash;
import org.example.kvstore.distribution.Strategy;
import org.jgroups.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class StoreImpl<K,V> extends ReceiverAdapter implements Store<K,V> {

    public static final String CLUSTER_NAME =   "_KVS";

    /**
     * Name
     */
    private String name;

    /**
     * Defines operations to know where data are stored
     */
    private Strategy strategy;

    /**
     * Node's data
     */
    private Map<K, V> data;
    private final String datalock = "_LOCK_";

    /**
     * ?
     */
    private CommandFactory<K, V> factory;

    /**
     * Workers
     */
    private ExecutorService workers;

    /**
     * Communication channel
     */
    private JChannel channel;

    /**
     * To handle remote call
     */
    private CompletableFuture<V> pending;

    /**
     * @param name
     *              nom
     */
    public StoreImpl(String name) {
        this.name = name;
    }

    /**
     * Initialize a node
     */
    public void init() throws Exception {
        this.data = new HashMap<>();
        this.factory = new CommandFactory<K, V>();
        this.workers = Executors.newCachedThreadPool();
        this.channel = new JChannel();
        this.channel.setReceiver(this);
        this.channel.connect(CLUSTER_NAME);
        this.channel.getState(null, 10000);
    }

    /**
     * Execute a command
     */
    public synchronized V execute(Command cmd) {
        if (cmd instanceof Get) {
            return this.get((K)cmd.getKey());
        } else if (cmd instanceof Put) {
            return this.put((K) cmd.getKey(), (V) cmd.getValue());
        } else {
            return null;
        }
    }

    /**
     * Perform a remote call
     * @param dst
     *              destination
     * @param command
     *              command
     */
    private void send(Address dst, Command command) throws Exception {
        Message msg = new Message(dst, null, command);
        this.channel.send(msg);
    }

    @Override
    public void receive(Message msg) {
        Address src = msg.getSrc();
        Command cmd = (Command) msg.getObject();
        this.workers.submit(new CmdHandler(src, cmd));
    }

    @Override
    public void viewAccepted(View view) {
        this.strategy = new ConsistentHash(view);
    }

    @Override
    public V get(K k) {
        Address owner = strategy.lookup(k);
        if (owner.equals(channel.getAddress())) {
            return data.get(k);
        }
        try {
            this.pending = new CompletableFuture();
            this.send(owner, factory.newGetCmd(k));
            return this.pending.get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public V put(K k, V v) {
        Address owner = strategy.lookup(k);
        if (owner.equals(channel.getAddress())) {
            V old = data.get(k);
            data.put(k, v);
            return old;
        }
        try {
            this.pending = new CompletableFuture<>();
            this.send(owner, factory.newPutCmd(k, v));
            return this.pending.get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String toString(){
        return "Store#"+name+"{"+data.toString()+"}";
    }

    /**
     * Threads which handle RPC calls
     */
    private class CmdHandler implements Callable<Void> {

        /**
         * Caller
         */
        private Address source;

        /**
         * Command to execute
         */
        private Command command;

        /**
         * Creates a command handler
         * @param source
         *              caller's address
         * @param command
         *              command to execute
         */
        public CmdHandler(Address source, Command command) {
            this.source = source;
            this.command = command;
        }

        @Override
        public Void call() throws Exception {
            // If the registered message is a reply, then the future is updated
            // Otherwise, it treats the command, and sends back the response to the caller
            if (this.command instanceof Reply) {
                pending.complete((V)command.getValue());
            } else {
                Reply reply;
                synchronized (datalock) {
                    reply = factory.newReplyCmd((K) command.getKey(), data.get(command.getKey()));
                    if (command instanceof Put) {
                        data.put((K) command.getKey(), (V) command.getValue());
                    }
                }
                send(source, reply);
            }
            return null;
        }
    }

}
