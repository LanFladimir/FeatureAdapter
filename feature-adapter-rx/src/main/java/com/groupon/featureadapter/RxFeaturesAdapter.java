/*
 * Copyright (c) 2017, Groupon, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.groupon.featureadapter;

import static rx.Observable.from;
import static rx.Observable.just;
import static rx.android.schedulers.AndroidSchedulers.mainThread;
import static rx.schedulers.Schedulers.computation;

import android.support.v7.widget.RecyclerView;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import rx.Observable;
import rx.functions.Func2;
import rx.subjects.BehaviorSubject;

public class RxFeaturesAdapter<MODEL> extends FeaturesAdapter<MODEL> {

  private final FeatureUpdateComparator<MODEL> featureUpdateComparator;
  private RecyclerView recyclerView;

  public RxFeaturesAdapter(List<FeatureController<MODEL>> featureControllers) {
    super(featureControllers);
    featureUpdateComparator = new FeatureUpdateComparator<>(getFeatureControllers());
  }

  @Override
  public void onAttachedToRecyclerView(RecyclerView recyclerView) {
    super.onAttachedToRecyclerView(recyclerView);
    this.recyclerView = recyclerView;
  }

  @Override
  public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
    super.onDetachedFromRecyclerView(recyclerView);
    this.recyclerView = null;
  }

  /**
   * Calculates each feature's new items and diff in parallel in the computation scheduler pool,
   * then dispatches feature updates to adapter in feature order.
   *
   * @param modelObservable the stream of models
   * @return an observable of {@link FeatureUpdate} for tracking the adapter changes.
   */
  public Observable<List<FeatureUpdate>> updateFeatureItems(Observable<MODEL> modelObservable) {
    // the ticker observable is gonna emit an item every time all the
    // list of items from all the feature controllers have been computed
    // so we just process the model instances one at a time
    // this is meant to be a very fine grained back pressure mechanism.
    BehaviorSubject<Object> tickObservable = BehaviorSubject.create();
    tickObservable.onNext(null);
    return modelObservable
        .observeOn(mainThread())
        .zipWith(tickObservable, (model, tick) -> model)
        .onBackpressureLatest()
        .flatMap(
            model ->
                from(getFeatureControllers())
                    .flatMap(
                        // each feature controller receives a fork of the model observable
                        // and compute its items in parallel, and then updates the UI ASAP
                        // but we still aggregate all the list to be sure to pace the model
                        // observable
                        // correctly using the tick observable
                        feature ->
                            just(feature)
                                .observeOn(computation())
                                .map(featureController -> toFeatureUpdate(featureController, model))
                                .filter(featureUpdate -> featureUpdate != null))
                    // collect all observable of feature updates in a list in feature order
                    .toSortedList(featureUpdateComparator::compare)
                    .observeOn(mainThread())
                    // dispatch each feature update in order to the adapter
                    // (this also updates the internal adapter state)
                    .map(this::dispatchFeatureUpdates)
                    .map(
                        list -> {
                          tickObservable.onNext(null);
                          if (recyclerView != null) {
                            recyclerView.setItemViewCacheSize(getItemCount());
                          }
                          return list;
                        }));
  }

  private static class FeatureUpdateComparator<T> implements Comparator<FeatureUpdate> {

    private final Map<FeatureController, Integer> mapFeatureControllerToIndex =
        new IdentityHashMap<>();

    FeatureUpdateComparator(List<FeatureController<T>> featureControllers) {
      int idx = 0;
      for (FeatureController featureController : featureControllers) {
        mapFeatureControllerToIndex.put(featureController, idx++);
      }
    }

    @Override
    public int compare(FeatureUpdate o1, FeatureUpdate o2) {
      return mapFeatureControllerToIndex.get(o1.featureController)
          - mapFeatureControllerToIndex.get(o2.featureController);
    }
  }

  private class ActionReducer implements Func2<MODEL, MODEL, MODEL> {
    @Override
    public MODEL call(MODEL model0, MODEL model1) {
      return model1;
    }
  }
}
