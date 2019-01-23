# Compte-rendu TP2 - Key-value store

COROLLER Stevan

COSMIDES Mélanie

PIPEREAU Yohan

ZIRNHELD Rémy

Le but de ce TP est d'implémenter un key-value store (KVS), un modèle
NoSQL basique. Pour ce faire, nous avons tout d'abord implémenté un KVS
sans traiter les cas ou le nombre de noeuds change, cas qui implique une
redistribution des données. Nous avons dans un second temps implémenté
la partie migration de données.

## Un simple KVS

### Localisation des données

Nous avons tout d'abord implémenté la partie localisation des données,
c'est-à-dire les opérations qui, à partir d'une clé, permettent de connaître
le noeud sur lequel se trouve la donnée associée. Pour cela, on représente
l'ensemble des clés comme un anneau, une liste d'entiers dans notre cas,
qui représente toute les clés possibles. Cet anneau est découpé en différentes
parties, chaque partie représentant une liste de clés. On associe à chacune
de ces parties un noeud qui stocke donc toutes les données de la liste de
clés représentée.

Dans notre implémentation, on représente les clés comme des `int` en
prenant le hashCode des clés, et les identifiants de noeuds sont des
objet de type `Address`. Cela implique que l'opération hashCode est la
même pour tous les noeuds, c'est-à-dire que pour une clé donnée, deux
noeuds donneront le même hashCode à la clé. Cette partie est implémentée
dans la classe `ConsistenHash`, qui construit à partir d'une `View`,
c'est-à-dire à partir du nombre de noeuds dans le cluster, l'anneau
représentant les clés ainsi que le dictionnaire liant liste de clés et noeud
de stockage.

Ainsi, la méthode `ConsitentHash.lookup(Object key)` retourne l'addresse
du noeud qui contient la donnée qui a pour clé 'key'. Le but est de pouvoir
addresser sa requête à n'importe quel noeud qui redirigera la requête,
et donc d'éviter un goulot d'étranglement sur un serveur "coordinateur".

### Gestion des requêtes

Lors d'une requête sur un noeud (Put ou Get), deux comportements peuvent se
présenter :

* Ou bien la donnée est sur le noeud en question, et dans ce cas le noeud
  répond tout simplement à la requête

* Ou bien la donnée est sur un autre noeud : dans ce cas, le noeud à qui
  on a demandé la donnée envoie un message au noeud ayant la donnée,
  message contenant la requête associée (via la methode `send(Address, Command)`).
  On définit alors dans la methode `receive(Message)` l'enregistrement
  d'un `Callable` qui définit l'action à faire : executer la commande
  et envoyer un message de type `Reply` au noeud appelé. Ce message contient le
  resultat de la commande. Les traitements des messages étant tous définis
  dans la `CmdHandler`, on définit également l'action à faire en cas de
  message de type `Reply`, qui consiste simplement en l'assignation d'une
  variable lue par le Thread principal.

Une fois cela fait, nous avons également protégé les sections critiques
des noeuds afin de permettre aux workers de travailler en même temps.

## KVS avec migration de données

Dans notre implémentation du KVS, chaque serveur gère ses données qui sont
représentées en RAM par des dictionnaires. Si un serveur tombe, il n'y a
alors aucun moyen de récupérer les données, donc de les migrer. Nous avons
donc réfléchis uniquement à un moyen de re-répartir les clés en cas d'ajout
ou de retrait de serveur.

Dans un cas réel, il faudrait fournir aux serveurs un moyen de récupérer
les données stockées dans les données locales (persistantes) de chaque
serveur. Idéalement, on éviterait la problématique de la migration en ayant
une gestion de réplicas pour chaque partie de l'ensemble des clés.

Nous proposons donc deux manières de re-répartir les clés :
* En privilégiant l'équilibrage de la charge entre les serveurs : lorsqu'un
  serveur tombe ou lorsqu'un serveur revient, on redécoupe l'ensemble des clés
  de manière équitable dans le même ordre qu'à l'origine. Ainsi, chaque serveur
  sait quels couples clé-valeur il doit recevoir et envoyer. Cela a pour
  avantage de répartir la charge, mais cela implique beaucoup de communication
  entre les serveurs, et donc une plus faible disponibilité.

* En privilégiant la performance et la disponibilité : lors d'un changement
  de vue, le serveur possédant la section de l'espace de clés directement inférieure au
  serveur tombé au combat récupère tout la section de l'espace de clés gérée par ce dernier.
  Cela a pour avantage de ne rendre indisponible que les serveurs concernés,
  mais cela a un impact sur la répartition de charge, puisque celui qui a
  récupéré les clés va logiquement recevoir davantage de requêtes que les
  autres. En pratique, cet état est temporaire puisque le serveur tombé sera
  redémarré et récuperera ses clés, ré-homogénisant la charge.

## Annexes
### Un simple KVS - Code

1. ConsistentHash.java :

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

2. StoreImpl.java :

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
