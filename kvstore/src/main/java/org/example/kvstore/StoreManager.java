package org.example.kvstore;

public class StoreManager {

    /**
     * Default name
     */
    public static final String DEFAULT_STORE = "__kvstore";

    /**
     * Create a node
     */
    public <K,V> Store<K,V> newStore() {
        return newStore(DEFAULT_STORE);
    }

    /**
     * Create a node
     * @param name
     *              node's name
     */
    public <K,V> Store<K,V> newStore(String name){
        try {
            StoreImpl<K,V> store = new StoreImpl(name);
            store.init();
            return store;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
