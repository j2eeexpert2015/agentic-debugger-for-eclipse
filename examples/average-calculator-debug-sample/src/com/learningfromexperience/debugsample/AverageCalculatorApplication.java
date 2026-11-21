package com.learningfromexperience.debugsample;

import com.learningfromexperience.debugsample.service.AverageCalculator;

/**
 * Runs the sample calculator from a Java main method.
 *
 * @author Ayan Dutta
 */
public final class AverageCalculatorApplication {
    public static void main(String[] args) {
        int[] measurements = {10, 20, 31};
        AverageCalculator calculator = new AverageCalculator();
        double average = calculator.calculateAverage(measurements);
        System.out.println("Average: " + average);
    }
}
