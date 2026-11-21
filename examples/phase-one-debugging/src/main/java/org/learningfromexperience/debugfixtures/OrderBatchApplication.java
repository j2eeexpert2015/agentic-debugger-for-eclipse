package org.learningfromexperience.debugfixtures;

public class OrderBatchApplication {
    public static void main(String[] args) throws InterruptedException {
        for (int id = 1000; id < 2000; id++) {
            double amount = 120.0;
            int discountPercent = id == 1057 ? 10 : 0;
            double result = OrderCalculator.calculate(id, amount, discountPercent);
            if (id == 1057) System.out.printf("Order %d total: %.2f%n", id, result);
        }
        Thread.sleep(3000);
    }
}
