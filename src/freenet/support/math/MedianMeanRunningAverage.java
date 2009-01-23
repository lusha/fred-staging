package freenet.support.math;

import java.util.ArrayList;

/**
 * A RunningAverage that tracks both the median and mean of a series of values.
 * WARNING: Uses memory and proportional to the number of reports! Only for debugging!
 * (Also uses CPU time O(N log N) with the number of reports in currentValue()).
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class MedianMeanRunningAverage implements RunningAverage {
	
	final ArrayList<Double> reports;
	final TrivialRunningAverage mean;

	public MedianMeanRunningAverage() {
		reports = new ArrayList<Double>();
		mean = new TrivialRunningAverage();
	}

	public MedianMeanRunningAverage(MedianMeanRunningAverage average) {
		this.mean = new TrivialRunningAverage(average.mean);
		this.reports = new ArrayList<Double>();
		reports.addAll(average.reports);
	}

	public Object clone() {
		return new MedianMeanRunningAverage(this);
	}

	public synchronized long countReports() {
		return reports.size();
	}

	public synchronized double currentValue() {
		int size = reports.size();
		int middle = size / 2;
		java.util.Collections.sort(reports);
		return reports.get(middle);
	}

	public synchronized void report(double d) {
		mean.report(d);
		reports.add(d);
	}

	public void report(long d) {
		report((double)d);
	}

	public double valueIfReported(double r) {
		throw new UnsupportedOperationException();
	}
	
	public synchronized String toString() {
		return "Median "+currentValue()+" mean "+mean.currentValue();
	}
	
	public synchronized double meanValue() {
		return mean.currentValue();
	}

}