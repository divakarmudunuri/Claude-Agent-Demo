package sample;

public class SequencePrinter {
    private static final int THREADS = 10, MAX = 100;
    private final Object lock = new Object();
    private int next = 1;

    void run(int id) {
        while (true) {
            synchronized (lock) {
                while (next <= MAX && (next - 1) % THREADS != id) {
                    try { lock.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (next > MAX) { lock.notifyAll(); return; }
                System.out.println(Thread.currentThread().getName() + " -> " + next++);
                lock.notifyAll();
            }
        }
    }

    public static void main(String[] args) {
        SequencePrinter p = new SequencePrinter();
        for (int i = 0; i < THREADS; i++) {
            int id = i;
            new Thread(() -> p.run(id), "Thread" + i).start();
        }
    }
}
