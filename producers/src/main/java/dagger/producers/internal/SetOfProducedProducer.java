/*
 * Copyright (C) 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.producers.internal;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dagger.producers.Produced;
import dagger.producers.Producer;
import dagger.producers.monitoring.ProducerMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link Producer} implementation used to implement {@link Set} bindings. This producer returns a
 * future {@code Set<Produced<T>>} whose elements are populated by subsequent calls to the delegate
 * {@link Producer#get} methods.
 *
 * @author Jesse Beder
 * @since 2.0
 */
public final class SetOfProducedProducer<T> extends AbstractProducer<Set<Produced<T>>> {
  public static <T> Producer<Set<T>> create() {
    return SetProducer.create();
  }

  public static <T> SetOfProducedProducer.Builder<T> builder() {
    return new Builder<T>();
  }

  public static final class Builder<T> {
    private final List<Producer<T>> individualProducers = new ArrayList<Producer<T>>();
    private final List<Producer<Set<T>>> setProducers = new ArrayList<Producer<Set<T>>>();

    public Builder<T> addProducer(Producer<T> individualProducer) {
      assert individualProducer != null : "Codegen error? Null producer";
      individualProducers.add(individualProducer);
      return this;
    }

    public Builder<T> addSetProducer(Producer<Set<T>> multipleProducer) {
      assert multipleProducer != null : "Codegen error? Null producer";
      setProducers.add(multipleProducer);
      return this;
    }

    public SetOfProducedProducer<T> build() {
      assert !hasDuplicates(individualProducers)
          : "Codegen error?  Duplicates in the producer list";
      assert !hasDuplicates(setProducers)
          : "Codegen error?  Duplicates in the producer list";

      return new SetOfProducedProducer<T>(
          ImmutableSet.<Producer<T>>copyOf(individualProducers),
          ImmutableSet.<Producer<Set<T>>>copyOf(setProducers));
    }
  }

  /**
   * Returns true if at least one pair of items in (@code original) are equals.
   */
  private static boolean hasDuplicates(List<? extends Object> original) {
    return original.size() != ImmutableSet.copyOf(original).size();
  }

  private final ImmutableSet<Producer<T>> individualProducers;
  private final ImmutableSet<Producer<Set<T>>> setProducers;

  private SetOfProducedProducer(
      ImmutableSet<Producer<T>> individualProducers, ImmutableSet<Producer<Set<T>>> setProducers) {
    this.individualProducers = individualProducers;
    this.setProducers = setProducers;
  }

  /**
   * Returns a future {@link Set} of {@link Produced} values whose iteration order is that of the
   * elements given by each of the producers, which are invoked in the order given at creation.
   *
   * <p>If any of the delegate sets, or any elements therein, are null, then that corresponding
   * {@code Produced} element will fail with a NullPointerException.
   *
   * <p>Canceling this future will attempt to cancel all of the component futures; but if any of the
   * delegate futures fail or are canceled, this future succeeds, with the appropriate failed
   * {@link Produced}.
   *
   * @throws NullPointerException if any of the delegate producers return null
   */
  @Override
  public ListenableFuture<Set<Produced<T>>> compute(ProducerMonitor unusedMonitor) {
    List<ListenableFuture<Produced<Set<T>>>> futureProducedSets =
        new ArrayList<ListenableFuture<Produced<Set<T>>>>(
            individualProducers.size() + setProducers.size());
    for (Producer<T> producer : individualProducers) {
      // TODO(ronshapiro): Don't require individual productions to be added to a set just to be
      // materialized into futureProducedSets.
      futureProducedSets.add(
          Producers.createFutureProduced(
              Producers.createFutureSingletonSet(checkNotNull(producer.get()))));
    }
    for (Producer<Set<T>> producer : setProducers) {
      futureProducedSets.add(Producers.createFutureProduced(checkNotNull(producer.get())));
    }

    return Futures.transform(
        Futures.allAsList(futureProducedSets),
        new Function<List<Produced<Set<T>>>, Set<Produced<T>>>() {
          @Override
          public Set<Produced<T>> apply(List<Produced<Set<T>>> producedSets) {
            ImmutableSet.Builder<Produced<T>> builder = ImmutableSet.builder();
            for (Produced<Set<T>> producedSet : producedSets) {
              try {
                Set<T> set = producedSet.get();
                if (set == null) {
                  // TODO(beder): This is a vague exception. Can we somehow point to the failing
                  // producer? See the similar comment in the component writer about null
                  // provisions.
                  builder.add(
                      Produced.<T>failed(
                          new NullPointerException(
                              "Cannot contribute a null set into a producer set binding when it's"
                                  + " injected as Set<Produced<T>>.")));
                } else {
                  for (T value : set) {
                    if (value == null) {
                      builder.add(
                          Produced.<T>failed(
                              new NullPointerException(
                                  "Cannot contribute a null element into a producer set binding"
                                      + " when it's injected as Set<Produced<T>>.")));
                    } else {
                      builder.add(Produced.successful(value));
                    }
                  }
                }
              } catch (ExecutionException e) {
                builder.add(Produced.<T>failed(e.getCause()));
              }
            }
            return builder.build();
          }
        });
  }
}
