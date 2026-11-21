package org.learningfromexperience.debugfixtures;

public class NestedObjectApplication {
    record Address(String city) {}
    record Customer(Address address) {}
    record Item(int quantity, double price) {}
    record Order(Customer customer, Item[] items) {}

    public static void main(String[] args) {
        Order order = new Order(new Customer(new Address("Kolkata")),
                new Item[]{new Item(2, 60.0), new Item(1, 10.0)});
        double total = sum(order);
        System.out.println("Order total: " + total);
    }
    static double sum(Order order) {
        double result = 0;
        for (Item item : order.items()) result += item.price();
        return result;
    }
}
