package com.learningfromexperience.debugsample.service;

public final class AverageCalculator {
    public double calculateAverage(int[] numbers) {
        if (numbers == null || numbers.length == 0) {
            throw new IllegalArgumentException("numbers must not be empty");
        }
        int sum = 0;
        for (int number : numbers) {
            sum += number; // BREAKPOINT: line 10
        }
        return sum / numbers.length; // Intentional bug: integer division
    }
}
