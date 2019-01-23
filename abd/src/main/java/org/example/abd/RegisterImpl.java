package org.example.abd;

import org.example.abd.cmd.*;
import org.example.abd.quorum.Majority;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @see Register
 * @param <V>
 */
public class RegisterImpl<V> extends ReceiverAdapter implements Register<V>{

    private static final String JGROUPS_NAME    = "_ABD";

    /**
     * Name
     */
    private String name;

    /**
     * Command factory
     */
    private CommandFactory<V> factory;

    /**
     * Channel from JGroups library
     */
    private JChannel channel;

    /**
     * True if the server can write
     */
    private boolean isWritable;

    /**
     * Current content
     */
    private V value;

    /**
     * Content's label
     */
    private int label;

    /**
     * Max label
     */
    private int max;

    /**
     * System to handle quorum
     */
    private Majority quorumSystem;

    /**
     * Response of executions
     */
    private CompletableFuture<V> pending;

    /**
     * Current reply of the current command
     */
    private Command currentReply;

    /**
     * Replies' counter
     */
    private int counter;
    private final String lock = "A";

    /**
     * @param name  name of the register
     */
    public RegisterImpl(String name) {
        this.name = name;
        this.factory = new CommandFactory<>();
    }

    /**
     * Initialize fields
     * @param isWritable
     *              true if it's writable
     */
    public void init(boolean isWritable) throws Exception {
        this.label = 0;
        this.max = 0;
        this.counter = 0;
        this.value = null;
        this.currentReply = null;
        this.isWritable = isWritable;
        this.channel = new JChannel();
        this.channel.setReceiver(this);
        this.channel.connect(JGROUPS_NAME);
    }

    @Override
    public void viewAccepted(View view) {
        this.quorumSystem = new Majority(view);
    }

    // Client part

    @Override
    public V read() {
        Command<V> cmd = this.factory.newReadRequest();
        return execute(cmd);
    }

    @Override
    public void write(V v) {
        if (!isWritable) {
            throw new IllegalStateException(this + " is not writable");
        }
        max++;
        label++;
        Command<V> cmd = this.factory.newWriteRequest(v, label);
        execute(cmd);
    }

    /**
     * Execute the specified command
     * @param cmd   the command to execute
     */
    private synchronized V execute(Command cmd){
        System.out.println("Start execution...");
        pending = new CompletableFuture<V>();
        List<Address> servers = quorumSystem.pickQuorum();
        for (Address server : servers) {
            send(server, cmd);
        }
        V reply = null;
        System.out.println("Messages sent to " + servers.size() + " servers");
        try {
            reply = pending.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        pending = null;
        System.out.println("Execution end");
        return reply;
    }

    // Message handlers

    @Override
    public void receive(Message msg) {
        Command<V> cmd = (Command<V>) msg.getObject();
        if (cmd instanceof ReadRequest){
            // If it's a read request, just send back the node's value
            send(msg.getSrc(), factory.newReadReply(value, label));
        } else if (cmd instanceof WriteRequest) {
            // If it's a write request, write the new value if needed
            if (cmd.getTag() > label) {
                label = cmd.getTag();
                value = cmd.getValue();
            }
            send(msg.getSrc(), factory.newWriteReply());
        } else if (cmd instanceof ReadReply) {
            // If it's a read reply, increment counter and check if the received value is newer than the previous one
            // if so, update the current value
            // check the counter to fill the future if needed
            synchronized (lock) {
                counter++;
            }
            System.out.println("Quorum size : " + quorumSystem.quorumSize() + ", Counter : " + counter);
            if (currentReply == null || currentReply.getTag() < cmd.getTag()) {
                currentReply = cmd;
            }
            if (counter == quorumSystem.quorumSize()) {
                System.out.println("Read OK");
                pending.complete((V)currentReply.getValue());
                counter = 0;
            }
        } else if (cmd instanceof WriteReply) {
            // If it's a write reply, increment the counter and fill the future if needed
            counter++;
            if (counter == quorumSystem.quorumSize()) {
                System.out.println("Write OK");
                pending.complete(null);
                counter = 0;
            }
        }
    }

    /**
     * Sends a command to the destination
     * @param dst
     *              destination
     * @param command
     *              command to send
     */
    private void send(Address dst, Command command) {
        try {
            Message message = new Message(dst,channel.getAddress(), command);
            channel.send(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
