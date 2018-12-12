package org.example.kvstore;

import org.example.kvstore.cmd.CommandFactory;
import org.example.kvstore.distribution.ConsistentHash;
import org.example.kvstore.distribution.Strategy;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class StoreImpl<K,V> extends ReceiverAdapter implements Store<K,V> {

    /**
     * Nom ?
     */
    private String name;

    /**
     * Champs définissant le "plan" de stockage
     */
    private Strategy strategy;

    /**
     * Données du noeud
     */
    private Map<K, V> data;

    /**
     * ?
     */
    private CommandFactory<K, V> factory;

    /**
     * @param name
     *              nom ?
     */
    public StoreImpl(String name) {
        this.name = name;
    }

    /**
     * Initialise le noeud
     */
    public void init() throws Exception {
        this.data = new HashMap<>();
    }

    @Override
    public void viewAccepted(View view) {
        this.strategy = new ConsistentHash(view);
    }

    @Override
    public V get(K k) {
        return data.get(k);
    }

    @Override
    public V put(K k, V v) {
        V old = data.get(k);
        data.put(k, v);
        return old;
    }

    @Override
    public String toString(){
        return "Store#"+name+"{"+data.toString()+"}";
    }

}
