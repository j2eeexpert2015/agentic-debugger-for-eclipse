package test;

public class TestDebug {
    public static void main(String[] args) {
        int[] numbers = {10, 20, 31};
        int sum = 0;
        for (int number : numbers) {
            sum += number; // BREAKPOINT: line 8
        }
        double average = sum / numbers.length; // BUG: integer division
        System.out.println("Average: " + average);
    }
}
