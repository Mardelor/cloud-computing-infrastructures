package org.example.abd.quorum;

import org.jgroups.Address;
import org.jgroups.View;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Defines a the qurom i.e. all the possible majorities among the living nodes
 */
public class Majority {

    /**
     * The current view
     */
    private View view;

    /**
     * @param view  JGroups view
     */
    public Majority(View view){
        this.view = view;
    }

    /**
     * @return  minimum size of a quorum
     */
    public int quorumSize(){
        int n = this.view.getMembers().size();
        return n/2 + 1;
    }

    /**
     * @return  a random quorum
     */
    public List<Address> pickQuorum(){
        ArrayList<Address> list = new ArrayList<>(this.view.getMembers());
        Collections.shuffle(list);
        return list.subList(0, this.quorumSize());
    }
}
