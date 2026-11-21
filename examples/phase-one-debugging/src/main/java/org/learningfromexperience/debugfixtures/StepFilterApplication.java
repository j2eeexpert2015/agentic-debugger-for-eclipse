package org.learningfromexperience.debugfixtures;

import java.util.ArrayList;
import java.util.List;

public class StepFilterApplication {
    public static void main(String[] args) {
        List<Integer> values = new ArrayList<>();
        values.add(10);
        values.add(20);
        System.out.println("Result: " + sum(values));
    }
    static int sum(List<Integer> values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
