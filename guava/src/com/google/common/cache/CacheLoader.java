/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.cache;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.errorprone.annotations.CheckReturnValue;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * Computes or retrieves values, based on a key, for use in populating a {@link LoadingCache}.
 *
 * <p>Most implementations will only need to implement {@link #load}. Other methods may be
 * overridden as desired.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * CacheLoader<Key, Graph> loader = new CacheLoader<Key, Graph>() {
 *   public Graph load(Key key) throws AnyException {
 *     return createExpensiveGraph(key);
 *   }
 * };
 * LoadingCache<Key, Graph> cache = CacheBuilder.newBuilder().build(loader);
 * }</pre>
 *
 * <p>Since this example doesn't support reloading or bulk loading, it can also be specified much
 * more simply:
 *
 * <pre>{@code
 * CacheLoader<Key, Graph> loader = CacheLoader.from(key -> createExpensiveGraph(key));
 * }</pre>
 *
 * @author Charles Fry
 * @since 10.0
 */
@GwtCompatible(emulated = true)
public abstract class CacheLoader<K, V> {
  /** Constructor for use by subclasses. */
  protected CacheLoader() {}

  /**
   * Computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @return the value associated with {@code key}; <b>must not be null</b>
   * @throws Exception if unable to load the result
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *     treated like any other {@code Exception} in all respects except that, when it is caught,
   *     the thread's interrupt status is set
   */
  public abstract V load(K key) throws Exception;

  /**
   * Computes or retrieves a replacement value corresponding to an already-cached {@code key}. This
   * method is called when an existing cache entry is refreshed by {@link
   * CacheBuilder#refreshAfterWrite}, or through a call to {@link LoadingCache#refresh}.
   * kp 刷新一个已经存在的key，被 CacheBuilder#refreshAfterWrite 或 LoadingCache#refresh 调用
   *
   * <p>This implementation synchronously delegates to {@link #load}. It is recommended that it be
   * overridden with an asynchronous implementation when using {@link
   * CacheBuilder#refreshAfterWrite}.
   *
   * <p><b>Note:</b> <i>all exceptions thrown by this method will be logged and then swallowed</i>.
   *
   * @param key the non-null key whose value should be loaded
   * @param oldValue the non-null old value corresponding to {@code key}
   *                 旧值、非空
   * @return the future new value associated with {@code key}; <b>must not be null, must not return
   *     null</b>
   * @throws Exception if unable to reload the result
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *     treated like any other {@code Exception} in all respects except that, when it is caught,
   *     the thread's interrupt status is set
   * @since 11.0
   */
  @GwtIncompatible // Futures
  public ListenableFuture<V> reload(K key, V oldValue) throws Exception {
    checkNotNull(key);
    checkNotNull(oldValue);
    // load(key)计算出结果后、将结果包装为异步任务的结果
    return Futures.immediateFuture(load(key));
  }

  /**
   * Computes or retrieves the values corresponding to {@code keys}. This method is called by {@link
   * LoadingCache#getAll}.
   * kp 当LoadingCache#getAll的时候、调用此方法
   *
   * <p>If the returned map doesn't contain all requested {@code keys} then the entries it does
   * contain will be cached, but {@code getAll} will throw an exception. If the returned map
   * contains extra keys not present in {@code keys} then all returned entries will be cached, but
   * only the entries for {@code keys} will be returned from {@code getAll}.
   * kp 如果返回结果不包含所有的key、则该方法返回的有效 k-v将会被缓存、但是 getAll() 将会抛异常；
   *    如果返回了多余的数据、则所有的数据将会被缓存、但是只有 getAll参数key对应的数据才会在getAll中返回。
   *
   * <p>This method should be overridden when bulk(批量) retrieval is significantly more efficient than
   * many individual lookups. Note that {@link LoadingCache#getAll} will defer to individual calls
   * to {@link LoadingCache#get} if this method is not overridden.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   *             kp 非空、不重复的数据
   *
   * @return a map from each key in {@code keys} to the value associated with that key;
   *         <b>may not contain null values 不能包含null</b>
   *         kp 返回key及其对应的value
   *
   * @throws Exception if unable to load the result
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *     treated like any other {@code Exception} in all respects except that, when it is caught,
   *     the thread's interrupt status is set
   * @since 11.0
   */
  public Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
    // This will be caught by getAll(), causing it to fall back to multiple calls to
    // LoadingCache.get
    throw new UnsupportedLoadingOperationException();
  }

  /**
   * Returns a cache loader that uses {@code function} to load keys, without supporting either
   * reloading or bulk loading. This allows creating a cache loader using a lambda expression.
   * kp 见 FunctionToCacheLoader，使用指定的函数+key获取value。只有load
   *
   * @param function the function to be used for loading values; must never return {@code null}
   * @return a cache loader that loads values by passing each key to {@code function}
   */
  @CheckReturnValue
  public static <K, V> CacheLoader<K, V> from(Function<K, V> function) {
    return new FunctionToCacheLoader<>(function);
  }

  /**
   * Returns a cache loader based on an <i>existing</i> supplier instance. Note that there's no need
   * to create a <i>new</i> supplier just to pass it in here; just subclass {@code CacheLoader} and
   * implement {@link #load load} instead.
   * kp 见 SupplierToCacheLoader、使用指定的函数获取对应的value、忽略key。只有load
   *
   * @param supplier the supplier to be used for loading values; must never return {@code null}
   * @return a cache loader that loads values by calling {@link Supplier#get}, irrespective of the
   *     key
   */
  @CheckReturnValue
  public static <V> CacheLoader<Object, V> from(Supplier<V> supplier) {
    return new SupplierToCacheLoader<V>(supplier);
  }

  // kp 不同于 SupplierToCacheLoader，使用key获取对应的value
  private static final class FunctionToCacheLoader<K, V>
          extends CacheLoader<K, V>
          implements Serializable {
    private final Function<K, V> computingFunction;

    public FunctionToCacheLoader(Function<K, V> computingFunction) {
      this.computingFunction = checkNotNull(computingFunction);
    }

    @Override
    public V load(K key) {
      return computingFunction.apply(checkNotNull(key));
    }

    private static final long serialVersionUID = 0;
  }

  /**
   * kp 重要。
   * Returns a {@code CacheLoader} which wraps {@code loader},
   * executing calls to {@link CacheLoader#reload} using {@code executor}.
   * kp 使用 制定线程池执行 reload 去刷新已经存在的key。重点、只是异步执行 reload。
   *
   * <p>This method is useful only when {@code loader.reload} has a synchronous implementation, such
   * as {@linkplain #reload the default implementation}.
   *
   * @since 17.0
   */
  @CheckReturnValue
  @GwtIncompatible // Executor + Futures
  public static <K, V> CacheLoader<K, V> asyncReloading(
          final CacheLoader<K, V> loader,
          final Executor executor) {
    checkNotNull(loader);
    checkNotNull(executor);
    return new CacheLoader<K, V>() {
      @Override
      public V load(K key) throws Exception {
        return loader.load(key);
      }

      // kp 使用指定线程池执行 reload 的任务
      @Override
      public ListenableFuture<V> reload(final K key, final V oldValue) throws Exception {
        ListenableFutureTask<V> task = ListenableFutureTask.create(
                        new Callable<V>() {
                          @Override
                          public V call() throws Exception {
                            return loader.reload(key, oldValue).get();
                          }
                        });
        executor.execute(task);
        return task;
      }

      @Override
      public Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
        return loader.loadAll(keys);
      }
    };
  }

  // kp 使用指定函数获取对应的value、忽略key
  private static final class SupplierToCacheLoader<V>
          extends CacheLoader<Object, V>
          implements Serializable {
    private final Supplier<V> computingSupplier;

    // kp 构造函数
    public SupplierToCacheLoader(Supplier<V> computingSupplier) {
      this.computingSupplier = checkNotNull(computingSupplier);
    }

    // kp 使用指定函数获取key对应的value
    @Override
    public V load(Object key) {
      checkNotNull(key);
      return computingSupplier.get();
    }

    private static final long serialVersionUID = 0;
  }

  /**
   * Exception thrown by {@code loadAll()} to indicate that it is not supported.
   * kp RuntimeException、表明 CacheLoader不支持 loadAll
   *
   * @since 19.0
   */
  public static final class UnsupportedLoadingOperationException
      extends UnsupportedOperationException {
    // Package-private because this should only be thrown by loadAll() when it is not overridden.
    // Cache implementors may want to catch it but should not need to be able to throw it.
    UnsupportedLoadingOperationException() {}
  }

  /**
   * Thrown to indicate that an invalid response was returned from a call to {@link CacheLoader}.
   * kp 无效的返回值，运行时异常。
   *
   * @since 11.0
   */
  public static final class InvalidCacheLoadException
          extends RuntimeException {
    public InvalidCacheLoadException(String message) {
      super(message);
    }
  }
}
