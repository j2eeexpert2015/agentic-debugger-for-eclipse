package org.learningfromexperience.debugfixtures;

public class OrderCalculator {
    public static double calculate(int orderId, double amount, int discountPercent) {
        double discountRate = discountPercent / 100;
        double discount = amount * discountRate;
        return amount - discount;
    }
}
