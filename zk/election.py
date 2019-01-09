#!/usr/bin/env python2.7
import time, socket, os, uuid, sys, kazoo, logging, signal, inspect
from kazoo.client import KazooClient
from kazoo.client import KazooState
from kazoo.exceptions import KazooException

class Election:

    def __init__(self, zk, path, func, args):
        self.election_path = path
        self.zk = zk
        self.is_leader = False
        self.path = None
        if not (inspect.isfunction(func)) and not(inspect.ismethod(func)):
            logging.debug("not a function "+str(func))
            raise SystemError
        
    def is_leading(self):
        return self.is_leader

	# TODO perform the election
    def ballot(self, event):
        if (self.path == None) :
            self.path = zk.create(self.election_path + "/n_", "", ephemeral=True, sequence=True)
        children = zk.get_children(self.election_path)
        print "Leader expected : " + min(children)
        self.is_leader = (self.path == self.election_path + "/" + min(children))
        print "Elected ? " + str(self.is_leader) + " (id :" + self.path + ")"
        zk.get_children(self.election_path, watch=self.ballot)
                            
if __name__ == '__main__':
    zkhost = "127.0.0.1:2181" #default ZK host
    logging.basicConfig(format='%(asctime)s %(message)s',level=logging.DEBUG)
    if len(sys.argv) == 2:
        zkhost=sys.argv[1]
        print("Using ZK at %s"%(zkhost))

    zk = KazooClient(zkhost)
    zk.start()
    election = Election(zk,"/master", Election.ballot, [])
    election.ballot(None)
        
    while True:
        time.sleep(1)
