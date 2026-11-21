package org.learningfromexperience.debugfixtures;

public class ExceptionApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "uncaught".equals(args[0])) {
            throw new IllegalStateException("Invalid invoice state");
        }
        try {
            processOrder(0);
        } catch (IllegalStateException failure) {
            System.out.println("Handled: " + failure.getMessage());
        }
    }
    static void processOrder(int quantity) {
        if (quantity <= 0) throw new IllegalStateException("Quantity must be positive");
    }
}
