package org.example.kvstore.distribution;

import org.jgroups.Address;
import org.jgroups.View;

import java.util.*;

public class ConsistentHash implements Strategy {

    /**
     * Representation of the cluster
     */
    private TreeSet<Integer> ring;

    /**
     * Map Integer to addresses (node's id)
     */
    private Map<Integer, Address> addresses;

    /**
     * Build a ring from the living nodes
     * @param view
     *              the current view
     */
    public ConsistentHash(View view) {
        this.ring = new TreeSet<>();
        this.addresses = new HashMap<>();

        List<Address> memberList = view.getMembers();
        int size = memberList.size();
        int id;
        for (int i=0; i<size; i++) {
            id = i* (Integer.MAX_VALUE - Integer.MIN_VALUE)/size + Integer.MIN_VALUE;
            ring.add(id);
            addresses.put(id, memberList.get(i));
        }
    }

    @Override
    public Address lookup(Object key) {
        Integer id = ring.floor(key.hashCode());
        if (id != null) {
            return addresses.get(id);
        }
        return null;
    }
}
