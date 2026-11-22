package org.learningfromexperience.debugfixtures;

public class LongRunningApplication {
    public static void main(String[] args) throws InterruptedException {
        long count = 0;
        long deadline = System.nanoTime() + 8_000_000_000L*100;
        while (System.nanoTime() < deadline) {
            count++;
            if (count % 100_000 == 0) Thread.sleep(1);
        }
        System.out.println("Iterations: " + count);
    }
}
