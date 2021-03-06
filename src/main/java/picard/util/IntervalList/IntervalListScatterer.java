/*
 * The MIT License
 *
 * Copyright (c) 2018 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package picard.util.IntervalList;

import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.IntervalList;
import htsjdk.samtools.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * An interface for a class that scatters IntervalLists.
 */
public interface IntervalListScatterer {
    /**
     * Scatter an {@link IntervalList} into several IntervalLists. The default implementation
     * makes use of the other interfaced methods, and aims to provide a universal way to
     * scatter an IntervalList.
     *
     * @param inputList    IntervalList to be scattered
     * @param scatterCount ideal number of scatters generated.
     * @return Scattered {@link List} of IntervalLists,
     */
    default List<IntervalList> scatter(final IntervalList inputList, final int scatterCount) {
        if (scatterCount < 1) {
            throw new IllegalArgumentException("scatterCount < 1");
        }

        final IntervalList processedIntervalList = preprocessIntervalList(inputList);

        // How much "weight" should go into each sublist. The weight of an interval is determined by
        // listWeight() and intervalWeight(). The definition should be be a "norm", thus only the empty set has
        // weight zero, and the weight of anything should be positive.
        final long idealSplitWeight = deduceIdealSplitWeight(processedIntervalList, scatterCount);

        Log.getInstance(IntervalListScatterer.class).info("idealSplitWeight=" + idealSplitWeight);

        final List<IntervalList> accumulatedIntervalLists = new ArrayList<>();

        // The IntervalList to which interval are currently being added to.
        IntervalList runningIntervalList = new IntervalList(processedIntervalList.getHeader());

        // Use a DeQueue since algo will be adding and removing elements from head.
        final ArrayDeque<Interval> intervalQueue = new ArrayDeque<>(processedIntervalList.getIntervals());

        long weightRemaining = listWeight(processedIntervalList);

        // Continue processing as long as the queue is not empty, and still haven't generated all scattered lists
        while (!intervalQueue.isEmpty() && accumulatedIntervalLists.size() < scatterCount - 1) {
            final Interval interval = intervalQueue.pollFirst();
            final long currentSize = listWeight(runningIntervalList);

            // The mean expected size of the remaining divisions
            // Note 1: While this looks like double counting, it isn't. We subtract here the bases that are in the _current_ running intervalList,
            // and when we create a new intervalList (below) we modify weightRemaining.
            // Note 2: The -1 in the denominator is for "runningIntervalList" that isn't yet counted in accumulatedIntervalLists.size()

            final double projectedSizeOfRemainingDivisions = (weightRemaining - listWeight(runningIntervalList)) / ((double) scatterCount - accumulatedIntervalLists.size() - 1);

            // split current interval into part that will go into current list (first) and
            // other part that will get put back into queue for next list.
            final List<Interval> split = takeSome(interval, idealSplitWeight, currentSize, projectedSizeOfRemainingDivisions);
            if (split.size() != 2) {
                throw new IllegalStateException("takeSome should always return exactly 2 (possibly null) intervals.");
            }

            // push second element back to queue (if exists).
            if (split.get(1) != null) {
                intervalQueue.addFirst(split.get(1));
            }

            // if first is null, we are done with the current list, so pack it in.
            if (split.get(0) == null) {
                weightRemaining -= listWeight(runningIntervalList);
                //add running list to return value, and create new running list
                accumulatedIntervalLists.add(runningIntervalList);
                runningIntervalList = new IntervalList(processedIntervalList.getHeader());
            } else {
                runningIntervalList.add(split.get(0));
            }
        }

        // Flush the remaining intervals into the last list.
        runningIntervalList.addall(intervalQueue);

        // if last list isn't empty, add it to return value.
        if (!runningIntervalList.getIntervals().isEmpty()) {
            accumulatedIntervalLists.add(runningIntervalList);
        }

        return accumulatedIntervalLists;
    }

    /**
     * A function that will be called on an IntervalList prior to splitting it into sub-lists, and is a point where
     * implementations can chose to impose some conditions on the lists, for example, merging overlapping/abutting intervals,
     * removing duplicates, etc.
     * @param inputList the original {@link IntervalList}
     * @return the  IntervalList that will be split up by the scatterer.
     */
    default IntervalList preprocessIntervalList(final IntervalList inputList) {
        return inputList.sorted();
    }

    /**
     * A method that defines the "weight" of an interval list for the purpose of scattering. The class will attempt to create
     * sublists that all have similar weights.
     */
    long intervalWeight(final Interval interval);

    /**
     *
     * A method that defines the "weight" of an interval for the purpose of scattering. The class will attempt to create
     * sublists that all have similar weights. This method need to estimate the change in any sublists weight due to the possible
     * of the provided interval.
     */
    long listWeight(final IntervalList intervalList);

    /**
     * Figure out how much of the input interval to put into current list and how much to leave for the next interval list.
     *
     * @param interval
     * @return a list of two (possibly null) elements. The first element should be added to the current interval list, the second
     * should be offered to the next interval list.
     */
    List<Interval> takeSome(final Interval interval, final long idealSplitWeight, final long currentSize, final double projectSizeOfRemaining);

    /**
     * A method that determines the ideal target "weight" of the output IntervalList.
     * @param intervalList the {@link IntervalList} that is about to get split
     * @param nCount the scatter count into which to split intervalList
     * @return The ideal "weight" of the output {@link IntervalList}'s
     */
    int deduceIdealSplitWeight(final IntervalList intervalList, final int nCount);
}

