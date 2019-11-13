package org.bukkit.craftbukkit.v1_12_R1.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.apache.commons.lang.Validate;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/**
 * The fundamental concepts for this implementation:
 * <li>Main thread owns {@link #head} and {@link #currentTick}, but it may be read from any thread</li>
 * <li>Main thread exclusively controls {@link #temp} and {@link #pending}.
 *     They are never to be accessed outside of the main thread; alternatives exist to prevent locking.</li>
 * <li>{@link #head} to {@link #tail} act as a linked list/queue, with 1 consumer and infinite producers.
 *     Adding to the tail is atomic and very efficient; utility method is {@link #handle(CraftTask, long)} or {@link #addTask(CraftTask)}. </li>
 * <li>Changing the period on a task is delicate.
 *     Any future task needs to notify waiting threads.
 *     Async tasks must be synchronized to make sure that any thread that's finishing will remove itself from {@link #runners}.
 *     Another utility method is provided for this, {@link #cancelTask(int)}</li>
 * <li>{@link #runners} provides a moderately up-to-date view of active tasks.
 *     If the linked head to tail set is read, all remaining tasks that were active at the time execution started will be located in runners.</li>
 * <li>Async tasks are responsible for removing themselves from runners</li>
 * <li>Sync tasks are only to be removed from runners on the main thread when coupled with a removal from pending and temp.</li>
 * <li>Most of the design in this scheduler relies on queuing special tasks to perform any data changes on the main thread.
 *     When executed from inside a synchronous method, the scheduler will be updated before next execution by virtue of the frequent {@link #parsePending()} calls.</li>
 */
public class CraftScheduler implements BukkitScheduler {

    //private final Executor executor = Executors.newCachedThreadPool(new com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("Craft Scheduler Thread - %1$d").build()); // Spigot // Paper - moved to AsyncScheduler
    //private CraftAsyncDebugger debugHead = new CraftAsyncDebugger(-1, null, null) {@Override StringBuilder debugTo(StringBuilder string) {return string;}}; // Paper
    //private CraftAsyncDebugger debugTail = debugHead; // Paper
    private static final int RECENT_TICKS;

    static {
        RECENT_TICKS = 30;
    }

    // If the tasks should run on the same tick they should be run FIFO
    /**
     * Main thread logic only
     */
    final PriorityQueue<CraftTask> pending = new PriorityQueue<>(10, // Paper
            Comparator.comparingLong(CraftTask::getNextRun).thenComparingInt(CraftTask::getTaskId));
    /**
     * These are tasks that are currently active. It's provided for 'viewing' the current state.
     */
    final ConcurrentHashMap<Integer, CraftTask> runners = new ConcurrentHashMap<>(); // Paper
    /**
     * Counter for IDs. Order doesn't matter, only uniqueness.
     */
    private final AtomicInteger ids = new AtomicInteger(1);
    /**
     * Main thread logic only
     */
    private final List<CraftTask> temp = new ArrayList<>();
    // Paper start
    private final CraftScheduler asyncScheduler;
    private final boolean isAsyncScheduler;
    volatile int currentTick = -1; // Paper
    /**
     * Current head of linked-list. This reference is always stale, {@link CraftTask#next} is the live reference.
     */
    private volatile CraftTask head = new CraftTask();
    /**
     * Tail of a linked-list. AtomicReference only matters when adding to queue
     */
    private final AtomicReference<CraftTask> tail = new AtomicReference<>(head);
    /**
     * The sync task that is currently running on the main thread.
     */
    private volatile CraftTask currentTask = null;

    public CraftScheduler() {
        this(false);
    }

    public CraftScheduler(boolean isAsync) {
        this.isAsyncScheduler = isAsync;
        if (isAsync) {
            this.asyncScheduler = this;
        } else {
            this.asyncScheduler = new CraftAsyncScheduler();
        }
    }
    // Paper end

    private static void validate(final Plugin plugin, final Object task) {
        Validate.notNull(plugin, "Plugin cannot be null");
        Validate.notNull(task, "Task cannot be null");
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }
    }

    public int scheduleSyncDelayedTask(final Plugin plugin, final Runnable task) {
        return this.scheduleSyncDelayedTask(plugin, task, 0L);
    }

    public BukkitTask runTask(Plugin plugin, Runnable runnable) {
        return runTaskLater(plugin, runnable, 0L);
    }

    @Deprecated
    public int scheduleAsyncDelayedTask(final Plugin plugin, final Runnable task) {
        return this.scheduleAsyncDelayedTask(plugin, task, 0L);
    }

    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable runnable) {
        return runTaskLaterAsynchronously(plugin, runnable, 0L);
    }

    public int scheduleSyncDelayedTask(final Plugin plugin, final Runnable task, final long delay) {
        return this.scheduleSyncRepeatingTask(plugin, task, delay, CraftTask.NO_REPEATING);
    }

    public BukkitTask runTaskLater(Plugin plugin, Runnable runnable, long delay) {
        return runTaskTimer(plugin, runnable, delay, CraftTask.NO_REPEATING);
    }

    @Deprecated
    public int scheduleAsyncDelayedTask(final Plugin plugin, final Runnable task, final long delay) {
        return this.scheduleAsyncRepeatingTask(plugin, task, delay, CraftTask.NO_REPEATING);
    }

    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable runnable, long delay) {
        return runTaskTimerAsynchronously(plugin, runnable, delay, CraftTask.NO_REPEATING);
    }

    public int scheduleSyncRepeatingTask(final Plugin plugin, final Runnable runnable, long delay, long period) {
        return runTaskTimer(plugin, runnable, delay, period).getTaskId();
    }

    public BukkitTask runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period) {
        validate(plugin, runnable);
        if (delay < 0L) {
            delay = 0;
        }
        if (period == CraftTask.ERROR) {
            period = 1L;
        } else if (period < CraftTask.NO_REPEATING) {
            period = CraftTask.NO_REPEATING;
        }
        return handle(new CraftTask(plugin, runnable, nextId(), period), delay);
    }

    @Deprecated
    public int scheduleAsyncRepeatingTask(final Plugin plugin, final Runnable runnable, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, runnable, delay, period).getTaskId();
    }

    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable runnable, long delay, long period) {
        validate(plugin, runnable);
        if (delay < 0L) {
            delay = 0;
        }
        if (period == CraftTask.ERROR) {
            period = 1L;
        } else if (period < CraftTask.NO_REPEATING) {
            period = CraftTask.NO_REPEATING;
        }
        return handle(new CraftAsyncTask(this.asyncScheduler.runners, plugin, runnable, nextId(), period), delay); // Paper
    }

    public <T> Future<T> callSyncMethod(final Plugin plugin, final Callable<T> task) {
        validate(plugin, task);
        final CraftFuture<T> future = new CraftFuture<>(task, plugin, nextId());
        handle(future, 0L);
        return future;
    }

    public void cancelTask(final int taskId) {
        if (taskId <= 0) {
            return;
        }
        // Paper start
        if (!this.isAsyncScheduler) {
            this.asyncScheduler.cancelTask(taskId);
        }
        // Paper end
        CraftTask task = runners.get(taskId);
        if (task != null) {
            task.cancel0();
        }
        task = new CraftTask(
                new Runnable() {
                    public void run() {
                        if (!check(CraftScheduler.this.temp)) {
                            check(CraftScheduler.this.pending);
                        }
                    }

                    private boolean check(final Iterable<CraftTask> collection) {
                        final Iterator<CraftTask> tasks = collection.iterator();
                        while (tasks.hasNext()) {
                            final CraftTask task = tasks.next();
                            if (task.getTaskId() == taskId) {
                                task.cancel0();
                                tasks.remove();
                                if (task.isSync()) {
                                    runners.remove(taskId);
                                }
                                return true;
                            }
                        }
                        return false;
                    }
                }); // Paper
        handle(task, 0L);
        for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
            if (taskPending == task) {
                return;
            }
            if (taskPending.getTaskId() == taskId) {
                taskPending.cancel0();
            }
        }
    }

    public void cancelTasks(final Plugin plugin) {
        Validate.notNull(plugin, "Cannot cancel tasks of null plugin");
        // Paper start
        if (!this.isAsyncScheduler) {
            this.asyncScheduler.cancelTasks(plugin);
        }
        // Paper end
        final CraftTask task = new CraftTask(
                new Runnable() {
                    public void run() {
                        check(CraftScheduler.this.pending);
                        check(CraftScheduler.this.temp);
                    }

                    void check(final Iterable<CraftTask> collection) {
                        final Iterator<CraftTask> tasks = collection.iterator();
                        while (tasks.hasNext()) {
                            final CraftTask task = tasks.next();
                            if (task.getOwner().equals(plugin)) {
                                task.cancel0();
                                tasks.remove();
                                if (task.isSync()) {
                                    runners.remove(task.getTaskId());
                                }
                            }
                        }
                    }
                }); // Paper
        handle(task, 0L);
        for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
            if (taskPending == task) {
                break;
            }
            if (taskPending.getTaskId() != -1 && taskPending.getOwner().equals(plugin)) {
                taskPending.cancel0();
            }
        }
        for (CraftTask runner : runners.values()) {
            if (runner.getOwner().equals(plugin)) {
                runner.cancel0();
            }
        }
    }

    public void cancelAllTasks() {
        // Paper start
        if (!this.isAsyncScheduler) {
            this.asyncScheduler.cancelAllTasks();
        }
        // Paper end
        final CraftTask task = new CraftTask(
                () -> {
                    Iterator<CraftTask> it = CraftScheduler.this.runners.values().iterator();
                    while (it.hasNext()) {
                        CraftTask task1 = it.next();
                        task1.cancel0();
                        if (task1.isSync()) {
                            it.remove();
                        }
                    }
                    CraftScheduler.this.pending.clear();
                    CraftScheduler.this.temp.clear();
                }); // Paper
        handle(task, 0L);
        for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
            if (taskPending == task) {
                break;
            }
            taskPending.cancel0();
        }
        for (CraftTask runner : runners.values()) {
            runner.cancel0();
        }
    }

    public boolean isCurrentlyRunning(final int taskId) {
        // Paper start
        if (!isAsyncScheduler) {
            if (this.asyncScheduler.isCurrentlyRunning(taskId)) {
                return true;
            }
        }
        // Paper end
        final CraftTask task = runners.get(taskId);
        if (task == null) {
            return false;
        }
        if (task.isSync()) {
            return (task == currentTask);
        }
        final CraftAsyncTask asyncTask = (CraftAsyncTask) task;
        synchronized (asyncTask.getWorkers()) {
            return !asyncTask.getWorkers().isEmpty();
        }
    }

    public boolean isQueued(final int taskId) {
        if (taskId <= 0) {
            return false;
        }
        // Paper start
        if (!this.isAsyncScheduler && this.asyncScheduler.isQueued(taskId)) {
            return true;
        }
        // Paper end
        for (CraftTask task = head.getNext(); task != null; task = task.getNext()) {
            if (task.getTaskId() == taskId) {
                return task.getPeriod() >= CraftTask.NO_REPEATING; // The task will run
            }
        }
        CraftTask task = runners.get(taskId);
        return task != null && task.getPeriod() >= CraftTask.NO_REPEATING;
    }

    public List<BukkitWorker> getActiveWorkers() {
        // Paper start
        if (!isAsyncScheduler) {
            //noinspection TailRecursion
            return this.asyncScheduler.getActiveWorkers();
        }
        // Paper end
        final ArrayList<BukkitWorker> workers = new ArrayList<>();
        for (final CraftTask taskObj : runners.values()) {
            // Iterator will be a best-effort (may fail to grab very new values) if called from an async thread
            if (taskObj.isSync()) {
                continue;
            }
            final CraftAsyncTask task = (CraftAsyncTask) taskObj;
            synchronized (task.getWorkers()) {
                // This will never have an issue with stale threads; it's state-safe
                workers.addAll(task.getWorkers());
            }
        }
        return workers;
    }

    public List<BukkitTask> getPendingTasks() {
        final ArrayList<CraftTask> truePending = new ArrayList<>();
        for (CraftTask task = head.getNext(); task != null; task = task.getNext()) {
            if (task.getTaskId() != -1) {
                // -1 is special code
                truePending.add(task);
            }
        }

        final ArrayList<BukkitTask> pending = new ArrayList<>();
        for (CraftTask task : runners.values()) {
            if (task.getPeriod() >= CraftTask.NO_REPEATING) {
                pending.add(task);
            }
        }

        for (final CraftTask task : truePending) {
            if (task.getPeriod() >= CraftTask.NO_REPEATING && !pending.contains(task)) {
                pending.add(task);
            }
        }
        // Paper start
        if (!this.isAsyncScheduler) {
            pending.addAll(this.asyncScheduler.getPendingTasks());
        }
        // Paper end
        return pending;
    }

    /**
     * This method is designed to never block or wait for locks; an immediate execution of all current tasks.
     */
    public void mainThreadHeartbeat(final int currentTick) {
        // Paper start
        if (!this.isAsyncScheduler) {
            this.asyncScheduler.mainThreadHeartbeat(currentTick);
        }
        // Paper end
        this.currentTick = currentTick;
        final List<CraftTask> temp = this.temp;
        parsePending();
        while (isReady(currentTick)) {
            final CraftTask task = pending.remove();
            if (task.getPeriod() < CraftTask.NO_REPEATING) {
                if (task.isSync()) {
                    runners.remove(task.getTaskId(), task);
                }
                parsePending();
                continue;
            }
            if (task.isSync()) {
                currentTask = task;
                try {
                    task.run();
                } catch (final Throwable throwable) {
                    // Paper start
                    String msg = String.format(
                            "Task #%s for %s generated an exception",
                            task.getTaskId(),
                            task.getOwner().getDescription().getFullName());
                    task.getOwner().getLogger().log(
                            Level.WARNING,
                            msg,
                            throwable);
                    //task.getOwner().getServer().getPluginManager().callEvent(new ServerExceptionEvent(new ServerSchedulerException(msg, throwable, task)));
                    // Paper end
                } finally {
                    currentTask = null;
                }
                parsePending();
            } else {
                //debugTail = debugTail.setNext(new CraftAsyncDebugger(currentTick + RECENT_TICKS, task.getOwner(), task.getTaskClass())); // Paper
                task.getOwner().getLogger().log(Level.SEVERE, "Unexpected Async Task in the Sync Scheduler. Report this to Paper"); // Paper
                // We don't need to parse pending
                // (async tasks must live with race-conditions if they attempt to cancel between these few lines of code)
            }
            final long period = task.getPeriod(); // State consistency
            if (period > 0) {
                task.setNextRun(currentTick + period);
                temp.add(task);
            } else if (task.isSync()) {
                runners.remove(task.getTaskId());
            }
        }
        pending.addAll(temp);
        temp.clear();
        //debugHead = debugHead.getNextHead(currentTick); // Paper
    }

    protected void addTask(final CraftTask task) {
        final AtomicReference<CraftTask> tail = this.tail;
        CraftTask tailTask = tail.get();
        while (!tail.compareAndSet(tailTask, task)) {
            tailTask = tail.get();
        }
        tailTask.setNext(task);
    }

    protected CraftTask handle(final CraftTask task, final long delay) { // Paper
        // Paper start
        if (!this.isAsyncScheduler && !task.isSync()) {
            this.asyncScheduler.handle(task, delay);
            return task;
        }
        // Paper end
        task.setNextRun(currentTick + delay);
        addTask(task);
        return task;
    }

    private int nextId() {
        return ids.incrementAndGet();
    }

    void parsePending() { // Paper
        CraftTask head = this.head;
        CraftTask task = head.getNext();
        CraftTask lastTask = head;
        for (; task != null; task = (lastTask = task).getNext()) {
            if (task.getTaskId() == -1) {
                task.run();
            } else if (task.getPeriod() >= CraftTask.NO_REPEATING) {
                pending.add(task);
                runners.put(task.getTaskId(), task);
            }
        }
        // We split this because of the way things are ordered for all of the async calls in CraftScheduler
        // (it prevents race-conditions)
        for (task = head; task != lastTask; task = head) {
            head = task.getNext();
            task.setNext(null);
        }
        this.head = lastTask;
    }

    private boolean isReady(final int currentTick) {
        return !pending.isEmpty() && pending.peek().getNextRun() <= currentTick;
    }

    @Override
    public String toString() {
        // Paper start
        return "";
        /*
        int debugTick = currentTick;
        StringBuilder string = new StringBuilder("Recent tasks from ").append(debugTick - RECENT_TICKS).append('-').append(debugTick).append('{');
        debugHead.debugTo(string);
        return string.append('}').toString();
        */
        // Paper end
    }

    @Deprecated
    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task, delay);
    }

    @Deprecated
    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task);
    }

    @Deprecated
    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period);
    }

    @Deprecated
    @Override
    public BukkitTask runTask(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
        return runTask(plugin, (Runnable) task);
    }

    @Deprecated
    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
        return runTaskAsynchronously(plugin, (Runnable) task);
    }

    @Deprecated
    @Override
    public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) throws IllegalArgumentException {
        return runTaskLater(plugin, (Runnable) task, delay);
    }

    @Deprecated
    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay) throws IllegalArgumentException {
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }

    @Deprecated
    @Override
    public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period) throws IllegalArgumentException {
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }

    @Deprecated
    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period) throws IllegalArgumentException {
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }
}