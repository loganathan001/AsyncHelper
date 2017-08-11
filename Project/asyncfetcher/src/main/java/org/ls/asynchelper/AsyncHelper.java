package org.ls.asynchelper;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.ls.javautils.stream.StreamUtil;

/**
 * The AsyncHelper.
 */
public enum AsyncHelper {
	
	/** The instance. */
	INSTANCE;

	/**
	 * {@code Logger} for this class.
	 */
	static final Logger logger = Logger.getLogger(AsyncHelper.class.getName());
	
	/** The fork join pool. */
	private ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
	
	/** The scheduled executor service. */
	private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(ForkJoinPool.getCommonPoolParallelism());
	
	/** The future suppliers. */
	private Map<ObjectsKey, Supplier<? extends Object>> futureSuppliers = new ConcurrentHashMap<>();
	
	/** The original keys. */
	private Map<ObjectsKey, ObjectsKey> originalKeys = new ConcurrentHashMap<>();
	
	/** The multiple accessed keys. */
	private Map<ObjectsKey, ObjectsKey> multipleAccessedKeys = new ConcurrentHashMap<>();
	
	/** The multiple accessed values. */
	private Map<ObjectsKey, Object> multipleAccessedValues = new ConcurrentHashMap<>();

	/**
	 * Gets the fork join pool.
	 *
	 * @return the fork join pool
	 */
	public ForkJoinPool getForkJoinPool() {
		return forkJoinPool;
	}

	/**
	 * Sets the fork join pool.
	 *
	 * @param forkJoinPool the new fork join pool
	 */
	public void setForkJoinPool(ForkJoinPool forkJoinPool) {
		assert (forkJoinPool != null);
		this.forkJoinPool = forkJoinPool;
	}

	/**
	 * Async get.
	 *
	 * @param <T> the generic type
	 * @param supplier the supplier
	 * @return the optional
	 */
	public <T> Optional<T> asyncGet(Supplier<T> supplier) {
		ForkJoinTask<T> task = forkJoinPool.submit(() -> supplier.get());
		return safeGet(task);
	}

	/**
	 * Safe get.
	 *
	 * @param <T> the generic type
	 * @param task the task
	 * @return the optional
	 */
	private <T> Optional<T> safeGet(ForkJoinTask<T> task) {
		try {
			return Optional.ofNullable(task.get());
		} catch (InterruptedException | ExecutionException e) {
			return Optional.empty();
		}
	}

	/**
	 * Safe supplier.
	 *
	 * @param <T> the generic type
	 * @param task the task
	 * @return the supplier
	 */
	private <T> Supplier<T> safeSupplier(ForkJoinTask<T> task) {
		return () -> {
			try {
				return task.get();
			} catch (InterruptedException | ExecutionException e) {
			}
			return null;
		};
	}

	/**
	 * Submit supplier.
	 *
	 * @param <T> the generic type
	 * @param supplier the supplier
	 * @return the supplier
	 */
	public <T> Supplier<T> submitSupplier(Supplier<T> supplier) {
		return safeSupplier(forkJoinPool.submit(() -> supplier.get()));
	}
	
	/**
	 * Submit multiple suppliers.
	 *
	 * @param <T> the generic type
	 * @param suppliers the suppliers
	 * @return the supplier[]
	 */
	@SuppressWarnings("unchecked")
	public <T> Supplier<T>[] submitMultipleSuppliers(Supplier<T>... suppliers) {
		return Stream.of(suppliers)
				.map(supplier -> submitSupplier(supplier))
				.toArray(size -> new Supplier[size]);
	}

	/**
	 * Submit callable.
	 *
	 * @param <T> the generic type
	 * @param callable the callable
	 * @return the supplier
	 */
	public <T> Supplier<T> submitCallable(Callable<T> callable) {
		return safeSupplier(forkJoinPool.submit(callable));
	}

	/**
	 * Submit and get.
	 *
	 * @param <T> the generic type
	 * @param callable the callable
	 * @return the optional
	 */
	public synchronized <T> Optional<T> submitAndGet(Callable<T> callable) {
		ForkJoinTask<T> task = forkJoinPool.submit(callable);
		return safeGet(task);
	}

	/**
	 * Submit task.
	 *
	 * @param runnable the runnable
	 */
	public void submitTask(Runnable runnable) {
		forkJoinPool.execute(runnable);
	}
	
	/**
	 * Submit tasks.
	 *
	 * @param runnables the runnables
	 */
	public void submitTasks(Runnable... runnables) {
		Stream.of(runnables).forEach(forkJoinPool::execute);
	}
	
	/**
	 * Schedule tasks.
	 *
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param runnables the runnables
	 */
	public void scheduleTasks(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Runnable... runnables) {
		doScheduleTasks(initialDelay, delay, unit, waitForPreviousTask, runnables);
	}
	
	/**
	 * Schedule tasks and wait.
	 *
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param runnables the runnables
	 */
	public void scheduleTasksAndWait(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Runnable... runnables) {
		try {
			doScheduleTasks(initialDelay, delay, unit, waitForPreviousTask, runnables).get();
		} catch (InterruptedException | ExecutionException | CancellationException e) {
			logger.config(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
	
	/**
	 * Do schedule tasks.
	 *
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param runnables the runnables
	 * @return the scheduled future
	 */
	private ScheduledFuture<?> doScheduleTasks(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Runnable... runnables) {
		final ScheduledFuture<?>[] scheduleFuture = new ScheduledFuture<?>[1];
		Runnable seq = new Runnable() {
			private AtomicInteger sno = new AtomicInteger(0);
			@Override
			public void run() {
				if(sno.get() < runnables.length) {
					runnables[sno.getAndIncrement()].run();
					
					if(sno.get() == runnables.length) {
						if(scheduleFuture[0] != null) {
							scheduleFuture[0].cancel(true);
						}
					}
				}
			}
		};
		
		if (waitForPreviousTask) {
			scheduleFuture[0] = scheduledExecutorService.scheduleWithFixedDelay(seq, initialDelay, delay, unit);
		} else {
			scheduleFuture[0] = scheduledExecutorService.scheduleAtFixedRate(seq, initialDelay, delay, unit);
		}
		
		return scheduleFuture[0];
	}
	
	/**
	 * Schedule multiple suppliers.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param suppliers the suppliers
	 * @return the supplier[]
	 */
	public <T>  Supplier<T>[] scheduleMultipleSuppliers(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			@SuppressWarnings("unchecked") Supplier<T>... suppliers) {
		return doScheduleSupplier(initialDelay, delay, unit, waitForPreviousTask, false, suppliers);
	}
	
	/**
	 * Schedule multiple suppliers and wait.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param suppliers the suppliers
	 * @return the stream
	 */
	public <T>  Stream<T> scheduleMultipleSuppliersAndWait(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			@SuppressWarnings("unchecked") Supplier<T>... suppliers) {
		 Supplier<T>[] scheduleSupplier = doScheduleSupplier(initialDelay, delay, unit, waitForPreviousTask, true, suppliers);
		 return Stream.of(scheduleSupplier).map(Supplier::get);
	}
	
	/**
	 * Schedule multiple suppliers for single access.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param suppliers the suppliers
	 * @param keys the keys
	 * @return true, if successful
	 */
	public <T>  boolean scheduleMultipleSuppliersForSingleAccess(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Supplier<T>[] suppliers, Object... keys) {
		Supplier<T>[] resultSuppliers = doScheduleSupplier(initialDelay, delay, unit, waitForPreviousTask, false, suppliers);
		boolean result = true;
		for (int i = 0; i < resultSuppliers.length; i++) {
			Supplier<T> supplier = resultSuppliers[i];
			Object[] indexedKey = getIndexedKey(i, keys);
			result &= storeSupplier(ObjectsKey.of(indexedKey), supplier, false);
		}
		return result;
	}
	
	/**
	 * Do schedule supplier.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param delay the delay
	 * @param unit the unit
	 * @param waitForPreviousTask the wait for previous task
	 * @param waitForAllTasks the wait for all tasks
	 * @param suppliers the suppliers
	 * @return the supplier[]
	 */
	private <T> Supplier<T>[] doScheduleSupplier(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			boolean waitForAllTasks, @SuppressWarnings("unchecked") Supplier<T>... suppliers) {
		final ScheduledFuture<?>[] scheduleFuture = new ScheduledFuture<?>[1];
		@SuppressWarnings("unchecked")
		Supplier<T>[] resultSuppliers = new Supplier[suppliers.length]; 
		Runnable seq = new Runnable() {
			private AtomicInteger sno = new AtomicInteger(0);
			@Override
			public void run() {
				if(sno.get() < suppliers.length) {
					final int current = sno.getAndIncrement();
					synchronized (resultSuppliers) {
						T res = suppliers[current].get();
						resultSuppliers[current] = () -> res;
						resultSuppliers.notifyAll();
					}
					
					if(sno.get() == suppliers.length) {
						if(scheduleFuture[0] != null) {
							scheduleFuture[0].cancel(true);
						}
					}
				}
			}
		};
		
		if (waitForPreviousTask) {
			scheduleFuture[0] = scheduledExecutorService.scheduleWithFixedDelay(seq, initialDelay, delay, unit);
		} else {
			scheduleFuture[0] = scheduledExecutorService.scheduleAtFixedRate(seq, initialDelay, delay, unit);
		}
		
		if(waitForAllTasks) {
			try {
			scheduleFuture[0].get();
			} catch (InterruptedException | ExecutionException | CancellationException e) {
				logger.config(e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
		
		@SuppressWarnings("unchecked")
		Supplier<T>[] blockingResultSupplier = new Supplier[suppliers.length]; 
		for (int i = 0; i < blockingResultSupplier.length; i++) {
			final int index = i;
			blockingResultSupplier[i] = new Supplier<T>() {
				@Override
				public synchronized T get() {
					synchronized (resultSuppliers) {
						while(resultSuppliers[index] == null) {
							try {
								resultSuppliers.wait();
							} catch (InterruptedException e) {
								logger.config(e.getClass().getSimpleName() + ": " + e.getMessage());
							}
						}
					}
					
					return resultSuppliers[index].get();
				}
			};
			
		}
		
		return blockingResultSupplier;
	}

	/**
	 * Submit supplier for multiple access.
	 *
	 * @param <T> the generic type
	 * @param supplier the supplier
	 * @param keys the keys
	 * @return true, if successful
	 */
	public <T> boolean submitSupplierForMultipleAccess(Supplier<T> supplier, Object... keys) {
		return doSubmitSupplier(supplier, true, keys);
	}
	
	/**
	 * Submit supplier for single access.
	 *
	 * @param <T> the generic type
	 * @param supplier the supplier
	 * @param keys the keys
	 * @return true, if successful
	 */
	public <T> boolean submitSupplierForSingleAccess(Supplier<T> supplier, Object... keys) {
		return doSubmitSupplier(supplier, false, keys);
	}
	
	/**
	 * Submit multiple suppliers for single access.
	 *
	 * @param <T> the generic type
	 * @param suppliers the suppliers
	 * @param keys the keys
	 * @return true, if successful
	 */
	public <T> boolean submitMultipleSuppliersForSingleAccess(Supplier<T>[] suppliers, Object... keys) {
		boolean result = true;
		for (int i = 0; i < suppliers.length; i++) {
			Supplier<T> supplier = suppliers[i];
			Object[] indexedKey = getIndexedKey(i, keys);
			result &= doSubmitSupplier(supplier, false, indexedKey);
		}
		return result;
	}

	/**
	 * Do submit supplier.
	 *
	 * @param <T> the generic type
	 * @param supplier the supplier
	 * @param multipleAccess the multiple access
	 * @param keys the keys
	 * @return true, if successful
	 */
	private <T> boolean doSubmitSupplier(Supplier<T> supplier, boolean multipleAccess, Object... keys) {
		ObjectsKey key = ObjectsKey.of(keys);
		if (!futureSuppliers.containsKey(key)) {
			Supplier<T> safeSupplier = safeSupplier(forkJoinPool.submit(() -> supplier.get()));
			return storeSupplier(key, safeSupplier, multipleAccess);
		}
		return false;
	}

	/**
	 * Store supplier.
	 *
	 * @param <T> the generic type
	 * @param key the key
	 * @param resultSupplier the result supplier
	 * @param multipleAccess the multiple access
	 * @return true, if successful
	 */
	private <T> boolean storeSupplier(ObjectsKey key, Supplier<T> resultSupplier, boolean multipleAccess) {
		if (!futureSuppliers.containsKey(key)) {
			futureSuppliers.put(key, resultSupplier);
			originalKeys.put(key, key);
			if (multipleAccess) {
				multipleAccessedKeys.put(key, key);
			} else {
				multipleAccessedKeys.remove(key);
			}

			if (multipleAccessedValues.containsKey(key)) {
				multipleAccessedValues.remove(key);
			}
			return true;
		}
		return false;
	}

	/**
	 * Submit task.
	 *
	 * @param runnable the runnable
	 * @param keys the keys
	 * @return true, if successful
	 */
	public boolean submitTask(Runnable runnable, Object... keys) {
		ObjectsKey key = ObjectsKey.of(keys);
		if (!futureSuppliers.containsKey(key)) {
			Supplier<Void> safeSupplier = safeSupplier(forkJoinPool.submit(() -> {
				runnable.run();
				return null;
			}));
			return storeSupplier(key, safeSupplier, false);
		}
		return false;
	}

	/**
	 * Wait and get.
	 *
	 * @param <T> the generic type
	 * @param clazz the clazz
	 * @param keys the keys
	 * @return the optional
	 */
	public <T> Optional<T> waitAndGet(Class<T> clazz, Object... keys) {
		ObjectsKey objectsKey = ObjectsKey.of(keys);
		if (originalKeys.containsKey(objectsKey)) {
			synchronized (originalKeys.get(objectsKey)) {
				if (multipleAccessedValues.containsKey(objectsKey)) {
					return getCastedValue(clazz, () -> multipleAccessedValues.get(objectsKey));
				}

				if (futureSuppliers.containsKey(objectsKey)) {
					Optional<T> value = getCastedValue(clazz, () -> futureSuppliers.get(objectsKey).get());
					futureSuppliers.remove(objectsKey);

					if (multipleAccessedKeys.containsKey(objectsKey)) {
						multipleAccessedValues.put(objectsKey, value.orElse(null));
					} else {
						originalKeys.remove(objectsKey);
					}
					return value;
				}
			}
		}
		return Optional.empty();
	}
	
	/**
	 * Wait and get multiple.
	 *
	 * @param <T> the generic type
	 * @param clazz the clazz
	 * @param keys the keys
	 * @return the stream
	 */
	public <T> Stream<T> waitAndGetMultiple(Class<T> clazz, Object... keys) {
		Stream.Builder<Optional<T>> builder = Stream.builder();
		for (int i = 0; originalKeys.containsKey(ObjectsKey.of(getIndexedKey(i, keys))); i++) {
			Object[] indexedKey = getIndexedKey(i, keys);
			builder.accept(waitAndGet(clazz, indexedKey));
		}
		return StreamUtil.flatten(builder.build());
	}

	/**
	 * Gets the indexed key.
	 *
	 * @param i the i
	 * @param keys the keys
	 * @return the indexed key
	 */
	private Object[] getIndexedKey(int i, Object... keys) {
		return Stream.concat(Stream.of(keys), Stream.of(i)).toArray();
	}

	/**
	 * Gets the casted value.
	 *
	 * @param <T> the generic type
	 * @param clazz the clazz
	 * @param supplier the supplier
	 * @return the casted value
	 */
	public <T> Optional<T> getCastedValue(Class<T> clazz, Supplier<? extends Object> supplier) {
		Object object = supplier.get();
		if (clazz.isInstance(object)) {
			return Optional.of(clazz.cast(object));
		}
		return Optional.empty();
	}

	/**
	 * Wait for task.
	 *
	 * @param keys the keys
	 */
	public void waitForTask(Object... keys) {
		ObjectsKey objectsKey = ObjectsKey.of(keys);
		if (originalKeys.containsKey(objectsKey)) {
			synchronized (originalKeys.get(objectsKey)) {
				if (multipleAccessedValues.containsKey(objectsKey)) {
					return;
				}
				if (futureSuppliers.containsKey(objectsKey)) {
					futureSuppliers.get(objectsKey).get();
					futureSuppliers.remove(objectsKey);

					if (multipleAccessedKeys.containsKey(objectsKey)) {
						multipleAccessedValues.put(objectsKey, objectsKey);
					} else {
						originalKeys.remove(objectsKey);
					}
				}
			}
		}
	}
	
	/**
	 * Wait for flag.
	 *
	 * @param flag the flag
	 * @throws InterruptedException the interrupted exception
	 */
	public void waitForFlag(String... flag) throws InterruptedException {
		ObjectsKey key = ObjectsKey.of((Object[])flag);
		ObjectsKey originalKey = originalKeys.get(key);
		if(originalKey == null) {
			originalKey = key;
			originalKeys.put(key, originalKey);
		}
		synchronized (originalKey) {
			originalKey.wait();
		}
	}
	
	/**
	 * Notify flag.
	 *
	 * @param flag the flag
	 */
	public void notifyFlag(String... flag) {
		ObjectsKey key = ObjectsKey.of((Object[])flag);
		ObjectsKey originalKey = originalKeys.get(key);
		if(originalKey != null) {
			originalKeys.remove(key);
			synchronized (originalKey) {
				originalKey.notify();
			}
		}
	}
	
	/**
	 * Notify all flag.
	 *
	 * @param flag the flag
	 */
	public void notifyAllFlag(String... flag) {
		ObjectsKey key = ObjectsKey.of((Object[])flag);
		ObjectsKey originalKey = originalKeys.get(key);
		if(originalKey != null) {
			originalKeys.remove(key);
			synchronized (originalKey) {
				originalKey.notifyAll();
			}
		}
	}

	/**
	 * Schedule supplier.
	 *
	 * @param <T>
	 *            the generic type
	 * @param initialDelay
	 *            the initial delay
	 * @param delay
	 *            the delay
	 * @param unit
	 *            the unit
	 * @param waitForPreviousTask
	 *            the wait for previous task
	 * @param supplier
	 *            the supplier
	 * @param times
	 *            the times
	 * @return the supplier[]
	 */
	public <T> Supplier<T>[] scheduleSupplier(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Supplier<T> supplier, int times) {
		return scheduleMultipleSuppliers(initialDelay, delay, unit, waitForPreviousTask,  arrayOfTimes(supplier, times));
	}

	/**
	 * Schedule supplier and wait.
	 *
	 * @param <T>
	 *            the generic type
	 * @param initialDelay
	 *            the initial delay
	 * @param delay
	 *            the delay
	 * @param unit
	 *            the unit
	 * @param waitForPreviousTask
	 *            the wait for previous task
	 * @param supplier
	 *            the supplier
	 * @param times
	 *            the times
	 * @return the stream
	 */
	public <T> Stream<T> scheduleSupplierAndWait(int initialDelay, int delay, TimeUnit unit,
			boolean waitForPreviousTask, Supplier<T> supplier, int times) {
		return scheduleMultipleSuppliersAndWait(initialDelay, delay, unit, waitForPreviousTask, arrayOfTimes(supplier, times));
	}

	/**
	 * Array of times.
	 *
	 * @param <T>
	 *            the generic type
	 * @param t
	 *            the t
	 * @param times
	 *            the times
	 * @return the t[]
	 */
	@SuppressWarnings("unchecked")
	private <T> T[] arrayOfTimes(T t, int times) {
		return Stream.generate(() -> t).limit(times).toArray(size -> (T[]) Array.newInstance(t.getClass(), size));
	}

	/**
	 * Schedule supplier for single access.
	 *
	 * @param <T>
	 *            the generic type
	 * @param initialDelay
	 *            the initial delay
	 * @param delay
	 *            the delay
	 * @param unit
	 *            the unit
	 * @param waitForPreviousTask
	 *            the wait for previous task
	 * @param supplier
	 *            the supplier
	 * @param times
	 *            the times
	 * @param keys
	 *            the keys
	 * @return true, if successful
	 */
	public <T> boolean scheduleSupplierForSingleAccess(int initialDelay, int delay, TimeUnit unit,
			boolean waitForPreviousTask, Supplier<T> supplier, int times, Object... keys) {
		return scheduleMultipleSuppliersForSingleAccess(initialDelay, delay, unit, waitForPreviousTask, arrayOfTimes(supplier, times), keys);
	}

	/**
	 * Schedule task.
	 *
	 * @param initialDelay
	 *            the initial delay
	 * @param delay
	 *            the delay
	 * @param unit
	 *            the unit
	 * @param waitForPreviousTask
	 *            the wait for previous task
	 * @param runnable
	 *            the runnable
	 * @param times
	 *            the times
	 */
	public void scheduleTask(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask, Runnable runnable,
			int times) {
		scheduleTasks(initialDelay, delay, unit, waitForPreviousTask,  arrayOfTimes(runnable, times));
	}

	/**
	 * Schedule task and wait.
	 *
	 * @param initialDelay
	 *            the initial delay
	 * @param delay
	 *            the delay
	 * @param unit
	 *            the unit
	 * @param waitForPreviousTask
	 *            the wait for previous task
	 * @param runnable
	 *            the runnable
	 * @param times
	 *            the times
	 */
	public void scheduleTaskAndWait(int initialDelay, int delay, TimeUnit unit, boolean waitForPreviousTask,
			Runnable runnable, int times) {
		scheduleTasksAndWait(initialDelay, delay, unit, waitForPreviousTask, arrayOfTimes(runnable, times));
	}

	/**
	 * Schedule supplier.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param unit the unit
	 * @param supplier the supplier
	 * @return the supplier[]
	 */
	public <T> Supplier<T> scheduleSupplier(int initialDelay, TimeUnit unit, Supplier<T> supplier) {
		return scheduleSupplier(initialDelay, 1, unit, false, supplier, 1)[0];
	}

	/**
	 * Schedule supplier and wait.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param unit the unit
	 * @param supplier the supplier
	 * @return the stream
	 */
	public <T> Optional<T> scheduleSupplierAndWait(int initialDelay, TimeUnit unit, Supplier<T> supplier) {
		return scheduleSupplierAndWait(initialDelay, 1, unit, false, supplier, 1).findAny();
	}

	/**
	 * Schedule supplier for single access.
	 *
	 * @param <T> the generic type
	 * @param initialDelay the initial delay
	 * @param unit the unit
	 * @param supplier the supplier
	 * @param keys the keys
	 * @return true, if successful
	 */
	public <T> boolean scheduleSupplierForSingleAccess(int initialDelay, TimeUnit unit, Supplier<T> supplier,
			Object... keys) {
		return scheduleSupplierForSingleAccess(initialDelay, 1, unit, false, supplier, 1, keys);
	}

	/**
	 * Schedule task.
	 *
	 * @param initialDelay the initial delay
	 * @param unit the unit
	 * @param runnable the runnable
	 */
	public void scheduleTask(int initialDelay, TimeUnit unit, Runnable runnable) {
		scheduleTask(initialDelay, 1, unit, false, runnable, 1);
	}

	/**
	 * Schedule task and wait.
	 *
	 * @param initialDelay the initial delay
	 * @param unit the unit
	 * @param runnable the runnable
	 */
	public void scheduleTaskAndWait(int initialDelay, TimeUnit unit, Runnable runnable) {
		scheduleTaskAndWait(initialDelay, 1, unit, false, runnable, 1);
	}

}
