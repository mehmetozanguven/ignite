/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.selection.cv;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.ml.Model;
import org.apache.ignite.ml.dataset.DatasetBuilder;
import org.apache.ignite.ml.dataset.impl.cache.CacheBasedDatasetBuilder;
import org.apache.ignite.ml.dataset.impl.local.LocalDatasetBuilder;
import org.apache.ignite.ml.math.Vector;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.selection.score.ScoreCalculator;
import org.apache.ignite.ml.selection.score.util.CacheBasedTruthWithPredictionCursor;
import org.apache.ignite.ml.selection.score.util.LocalTruthWithPredictionCursor;
import org.apache.ignite.ml.selection.score.util.TruthWithPredictionCursor;
import org.apache.ignite.ml.selection.split.mapper.SHA256UniformMapper;
import org.apache.ignite.ml.selection.split.mapper.UniformMapper;
import org.apache.ignite.ml.trainers.DatasetTrainer;

/**
 * Cross validation score calculator. Cross validation is an approach that allows to avoid overfitting that is made the
 * following way: the training set is split into k smaller sets. The following procedure is followed for each of the k
 * “folds”:
 * <ul>
 *     <li>A model is trained using k-1 of the folds as training data;</li>
 *     <li>the resulting model is validated on the remaining part of the data (i.e., it is used as a test set to compute
 *     a performance measure such as accuracy).</li>
 * </ul>
 *
 * @param <M> Type of model.
 * @param <L> Type of a label (truth or prediction).
 * @param <K> Type of a key in {@code upstream} data.
 * @param <V> Type of a value in {@code upstream} data.
 */
public class CrossValidationScoreCalculator<M extends Model<Vector, L>, L, K, V> {
    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Score calculator.
     * @param ignite Ignite instance.
     * @param upstreamCache Ignite cache with {@code upstream} data.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator, Ignite ignite,
        IgniteCache<K, V> upstreamCache, IgniteBiFunction<K, V, double[]> featureExtractor,
        IgniteBiFunction<K, V, L> lbExtractor, int cv) {
        return score(trainer, scoreCalculator, ignite, upstreamCache, (k, v) -> true, featureExtractor, lbExtractor,
            new SHA256UniformMapper<>(), cv);
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Base score calculator.
     * @param ignite Ignite instance.
     * @param upstreamCache Ignite cache with {@code upstream} data.
     * @param filter Base {@code upstream} data filter.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator, Ignite ignite,
        IgniteCache<K, V> upstreamCache, IgniteBiPredicate<K, V> filter,
        IgniteBiFunction<K, V, double[]> featureExtractor, IgniteBiFunction<K, V, L> lbExtractor, int cv) {
        return score(trainer, scoreCalculator, ignite, upstreamCache, filter, featureExtractor, lbExtractor,
            new SHA256UniformMapper<>(), cv);
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Base score calculator.
     * @param ignite Ignite instance.
     * @param upstreamCache Ignite cache with {@code upstream} data.
     * @param filter Base {@code upstream} data filter.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param mapper Mapper used to map a key-value pair to a point on the segment (0, 1).
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator,
        Ignite ignite, IgniteCache<K, V> upstreamCache, IgniteBiPredicate<K, V> filter,
        IgniteBiFunction<K, V, double[]> featureExtractor, IgniteBiFunction<K, V, L> lbExtractor,
        UniformMapper<K, V> mapper, int cv) {

        return score(
            trainer,
            predicate -> new CacheBasedDatasetBuilder<>(
                ignite,
                upstreamCache,
                (k, v) -> filter.apply(k, v) && predicate.apply(k, v)
            ),
            (predicate, mdl) -> new CacheBasedTruthWithPredictionCursor<>(
                upstreamCache,
                (k, v) -> filter.apply(k, v) && !predicate.apply(k, v),
                featureExtractor,
                lbExtractor,
                mdl
            ),
            featureExtractor,
            lbExtractor,
            scoreCalculator,
            mapper,
            cv
        );
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Base score calculator.
     * @param upstreamMap Map with {@code upstream} data.
     * @param parts Number of partitions.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator, Map<K, V> upstreamMap,
        int parts, IgniteBiFunction<K, V, double[]> featureExtractor, IgniteBiFunction<K, V, L> lbExtractor, int cv) {
        return score(trainer, scoreCalculator, upstreamMap, (k, v) -> true, parts, featureExtractor, lbExtractor,
            new SHA256UniformMapper<>(), cv);
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Base score calculator.
     * @param upstreamMap Map with {@code upstream} data.
     * @param filter Base {@code upstream} data filter.
     * @param parts Number of partitions.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator, Map<K, V> upstreamMap,
        IgniteBiPredicate<K, V> filter, int parts, IgniteBiFunction<K, V, double[]> featureExtractor,
        IgniteBiFunction<K, V, L> lbExtractor, int cv) {
        return score(trainer, scoreCalculator, upstreamMap, filter, parts, featureExtractor, lbExtractor,
            new SHA256UniformMapper<>(), cv);
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param scoreCalculator Base score calculator.
     * @param upstreamMap Map with {@code upstream} data.
     * @param filter Base {@code upstream} data filter.
     * @param parts Number of partitions.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param mapper Mapper used to map a key-value pair to a point on the segment (0, 1).
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    public double[] score(DatasetTrainer<M, L> trainer, ScoreCalculator<L> scoreCalculator, Map<K, V> upstreamMap,
        IgniteBiPredicate<K, V> filter, int parts, IgniteBiFunction<K, V, double[]> featureExtractor,
        IgniteBiFunction<K, V, L> lbExtractor, UniformMapper<K, V> mapper, int cv) {
        return score(
            trainer,
            predicate -> new LocalDatasetBuilder<>(
                upstreamMap,
                (k, v) -> filter.apply(k, v) && predicate.apply(k, v),
                parts
            ),
            (predicate, mdl) -> new LocalTruthWithPredictionCursor<>(
                upstreamMap,
                (k, v) -> filter.apply(k, v) && !predicate.apply(k, v),
                featureExtractor,
                lbExtractor,
                mdl
            ),
            featureExtractor,
            lbExtractor,
            scoreCalculator,
            mapper,
            cv
        );
    }

    /**
     * Computes cross-validated metrics.
     *
     * @param trainer Trainer of the model.
     * @param datasetBuilderSupplier Dataset builder supplier.
     * @param testDataIterSupplier Test data iterator supplier.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @param scoreCalculator Base score calculator.
     * @param mapper Mapper used to map a key-value pair to a point on the segment (0, 1).
     * @param cv Number of folds.
     * @return Array of scores of the estimator for each run of the cross validation.
     */
    private double[] score(DatasetTrainer<M, L> trainer, Function<IgniteBiPredicate<K, V>,
        DatasetBuilder<K, V>> datasetBuilderSupplier,
        BiFunction<IgniteBiPredicate<K, V>, M, TruthWithPredictionCursor<L>> testDataIterSupplier,
        IgniteBiFunction<K, V, double[]> featureExtractor, IgniteBiFunction<K, V, L> lbExtractor,
        ScoreCalculator<L> scoreCalculator, UniformMapper<K, V> mapper, int cv) {

        double[] scores = new double[cv];

        double foldSize = 1.0 / cv;
        for (int i = 0; i < cv; i++) {
            double from = foldSize * i;
            double to = foldSize * (i + 1);

            IgniteBiPredicate<K, V> trainSetFilter = (k, v) -> {
                double pnt = mapper.map(k, v);
                return pnt < from || pnt > to;
            };

            DatasetBuilder<K, V> datasetBuilder = datasetBuilderSupplier.apply(trainSetFilter);
            M mdl = trainer.fit(datasetBuilder, featureExtractor, lbExtractor);

            try (TruthWithPredictionCursor<L> cursor = testDataIterSupplier.apply(trainSetFilter, mdl)) {
                scores[i] = scoreCalculator.score(cursor.iterator());
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        return scores;
    }
}
