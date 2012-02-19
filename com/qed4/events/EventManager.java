package com.qed4.events;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This acts as a controller for firing events. For example if we have the
 * following <code>Event</code> object heirachy:
 *
 * <pre>
 *         ClientEvent extends Event
 *         LoginEvent extends ClientEvent
 *         LogoutEvent extends ClientEvent
 * </pre>
 *
 * Then add the following <code>EventListener</code> to the
 * <code>EventManager</code>:
 *
 * <pre>
 * addListener(LoginEvent.class, new EventListener() {
 * 	public void eventFired(Event e) {
 * 		System.out.println(e.getClass().getName());
 * 	}
 * });
 * </pre>
 *
 * The event listener will only be called when <code>fireEvent</code> fires a
 * new <code>LoginEvent</code> and the output will always be 'LoginEvent'.
 * However if we made the listener listen for
 * <code>addListener(ClientEvent.class,</code>... the event listener will be
 * called for every <code>LoginEvent</code>, <code>LogoutEvent</code> and
 * <code>ClientEvent</code> making the output either 'LoginEvent',
 * 'LogoutEvent' or 'ClientEvent'. Ideally each <code>Event</code> subclass
 * should be immutable so that all event listeners are guaranteed to receive the
 * same state.
 *
 */
public final class EventManager {

    private final Node root;
    private final Executor executor;
    private final Queue<Runnable> listenerQueue;
    private final Map<EventListener, WeakListener> weakListenerMap;
    private final ReferenceQueue<EventListener> referenceQueue;
    private final Queue<Event> eventQueue;
    private final AtomicBoolean inFire;

    public EventManager() {
        this(new SameThreadExecutor());
    }

    /**
     * This allows an executor to be choosen which will fire an event using the
     * provided executor. By default the events are fired on the same thread
     * that the <code>fireEvent</code> method is called.
     *
     * @param executor
     */
    public EventManager(Executor executor) {
        this.executor = executor;
        this.root = new Node(null, Object.class);

        this.listenerQueue = new ConcurrentLinkedQueue<Runnable>();
        this.referenceQueue = new ReferenceQueue<EventListener>();
        this.weakListenerMap = new WeakHashMap<EventListener, WeakListener>();

        this.eventQueue = new ConcurrentLinkedQueue<Event>();
        this.inFire = new AtomicBoolean(false);
    }

    /**
     * @param c
     *            the class which determines which events and subtypes of that
     *            event will be fired sent to <code>EventListener l</code>.
     * @param l
     *            the event listener that will be called when
     *            <code>fireEvent</code> fires an event or its subtype
     *            descrived in the first parameter.
     */
    public <T extends Event> void addListener(final Class<? extends T> c, final EventListener<T> l) {
        listenerQueue.add(new AddListener(c, l));
    }

    public <T extends Event> void removeListener(final Class<? extends T> c, final EventListener<T> l) {
        listenerQueue.add(new RemoveListener(c, l));
    }

    private static <T> List<Class<? super T>> getClassesInOrder(final Class<T> c) {
        final List<Class<? super T>> classOrder = new ArrayList<Class<? super T>>();
        Class<? super T> clazz = c;
        while (clazz != null) {
            classOrder.add(clazz);
            clazz = clazz.getSuperclass();
        }
        return classOrder;
    }

    private <T> Node getNode(final Class<T> c) {
        final List<Class<? super T>> classOrder = getClassesInOrder(c);
        // Starts at int i = classOrder.size() - 2 because the last class object will be
        // java.lang.Object as defined by the Java spec.
        Node current = this.root;
        for (int i = classOrder.size() - 2; i >= 0; i--) {

            final Class clazz = classOrder.get(i);

            Node next = current.getChildren().get(clazz);
            if (next == null) {
                next = new Node(current, clazz);
                next.getListeners().addAll(current.getListeners());
                current.getChildren().put(clazz, next);
            }
            current = next;
        }
        return current;
    }

    private void expungeTree() {
        for (Reference<? extends EventListener> weakRef = referenceQueue.poll();
                weakRef != null;
                weakRef = referenceQueue.poll()) {
            WeakListener wl = (WeakListener) weakRef;
            Node node = wl.getNode();
            Set<WeakListener> listeners = node.getListeners();
            listeners.remove(wl);
            // prune tree
            final Node parent = node.getParent();
            if (listeners.isEmpty() && parent != null) {
                parent.getChildren().remove(node.getClass());
            }
        }
    }

    private void fire() {
        if (inFire.getAndSet(true)) {
            return;
        } else {
            for (Event e = eventQueue.poll(); e != null; e = eventQueue.poll()) {

                final Event finalEvent = e;
                final Node node = getNode(e.getClass());
                for (final WeakListener l : node.getListeners()) {
                    final EventListener el = l.get();
                    // if has been garbage collected
                    if (el == null) {
                        continue;
                    }

                    executor.execute(new Runnable() {

                        @SuppressWarnings("unchecked")
                        @Override
                        public void run() {
                            el.eventFired(finalEvent);
                        }
                    });
                }
            }
            inFire.set(false);
        }
    }

    public synchronized void fireEvent(final Event e) {
        expungeTree();
        for (Runnable op = listenerQueue.poll(); op != null; op = listenerQueue.poll()) {
            op.run();
        }

        eventQueue.add(e);
        fire();
    }


    private static final class Node {

        private final Class c;
        private final Node parent;
        private final Map<Class, Node> children;
        private final Set<WeakListener> listeners;

        public Node(Node parent, Class c) {
            this.parent = parent;
            this.c = c;
            this.children = new HashMap<Class, Node>();
            this.listeners = new LinkedHashSet<WeakListener>();
        }

        public Map<Class, Node> getChildren() {
            return children;
        }

        public Set<WeakListener> getListeners() {
            return listeners;
        }

        public Class getNodeClass() {
            return c;
        }

        public Node getParent() {
            return parent;
        }
    }

    private static final class WeakListener extends WeakReference<EventListener> {

        private final Node node;

        private WeakListener(EventListener el, Node node,
                ReferenceQueue<EventListener> referenceQueue) {
            super(el, referenceQueue);
            this.node = node;
        }

        public Node getNode() {
            return node;
        }
    }

    private final class AddListener implements Runnable {

        private final Class<? extends Event> c;
        private final EventListener l;

        public AddListener(final Class<? extends Event> c, final EventListener l) {
            this.c = c;
            this.l = l;
        }

        @Override
        public void run() {
            final Node node = getNode(c);
            // add listener to all children of that node

            WeakListener wl = new WeakListener(l, node, referenceQueue);

            populateNodeBranch(node, wl);
            weakListenerMap.put(l, wl);
        }

        private void populateNodeBranch(Node node, WeakListener wl) {
            node.getListeners().add(wl);
            Collection<Node> children = node.getChildren().values();
            for (Node n : children) {
                populateNodeBranch(n, wl);
            }
        }
    }

    private final class RemoveListener implements Runnable {

        private final Class<? extends Event> c;
        private final EventListener l;

        public RemoveListener(final Class<? extends Event> c, final EventListener l) {
            this.c = c;
            this.l = l;
        }

        @Override
        public void run() {
            final Node node = getNode(c);

            WeakListener wl = weakListenerMap.remove(l);
            unpopulateNodeBranch(node, wl);
        }

        private void unpopulateNodeBranch(final Node node, final WeakListener wl) {
            node.getListeners().remove(wl);
            Collection<Node> children = node.getChildren().values();
            if (children.isEmpty() && node.getListeners().isEmpty() && node.getParent() != null) {
                node.getParent().getChildren().remove(node.getClass());
            } else {
                for (Node n : children) {
                    unpopulateNodeBranch(n, wl);
                }
            }
        }
    }

    private static final class SameThreadExecutor implements Executor {

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
