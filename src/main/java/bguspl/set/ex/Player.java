package bguspl.set.ex;

import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Dealer dealer;

    /**
     * keeps the tokens that are on the table for each player
     */
    public LinkedBlockingQueue <Integer> tokensTracking;

    /**
     * keeps the key presses for each player
     */
    public final LinkedBlockingQueue <Integer> keyPress;

    /**
     * keeps the last set that the player send to the dealer to check
     */
    public int [] set;
    private final int setSize;
    private final long pointFreeze;
    private final long penaltyFreeze;

    private boolean flagOnlyOnce;

    /**
     * prevent the players to put tokens when the card are replacing
     */
    private boolean flagRemoveCards;

    /**
     * put all the players in wait mode when card replacing
     */
    public boolean flagWaitRemoveCards;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * 0 - normal , 1 - point , 3 - penalty
     */
    public long penalty;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    public Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    public int score;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.dealer = dealer;
        this.tokensTracking = new LinkedBlockingQueue<>();
        this.keyPress = new LinkedBlockingQueue<>();
        setSize = 3;
        pointFreeze = env.config.pointFreezeMillis;
        penaltyFreeze = env.config.penaltyFreezeMillis;
        set = new int[setSize];
        flagOnlyOnce = false;
        flagRemoveCards = true;
        flagWaitRemoveCards = true;
        this.id = id;
        penalty = 0;
        this.human = human;
        terminate = false;
        this.score = 0;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            synchronized(this) {
                while (flagWaitRemoveCards && !terminate) {
                    try
                    {wait();}
                    catch (InterruptedException e) {
                        // TODO: handle exception
                    }
                }
            }
            try {
                Integer slot = keyPress.take();
                if (table.slotToCard[slot] != null) {
                    if (tokensTracking.contains(slot)) {
                        table.removeToken(id, slot);
                        tokensTracking.remove(slot);
                        dealer.deleteToken(id, slot);
                        flagOnlyOnce = false;
                    } else if (tokensTracking.size() < setSize) {
                        table.placeToken(id, slot);
                        tokensTracking.add(slot);
                        dealer.addToken(id, slot);
                        flagOnlyOnce = false;
                    }
                }
                if(tokensTracking.size() == setSize && !flagOnlyOnce) {
                    penalty = -1;
                    copyKeyPressTrackingToArrayOfCards();
                    flagOnlyOnce = true;
                    dealer.addPlayer(this);
                    synchronized (this) {
                        dealer.notifyDealer();
                        while (penalty == -1 && !terminate) {
                            try {
                                wait();
                            } catch (InterruptedException e) {
                                // TODO: handle exception
                            }
                        }
                    }
                }

            } catch (InterruptedException e) {
                // TODO: handle exception
            }
            if(penalty == 1){
                point();
                penalty = 0;
            }
            if(penalty == 3){
                penalty();
                penalty = 0;
            }

        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random();
            while (!terminate) {
                int slot = rand.nextInt(12);
                keyPressed(slot);
                   synchronized(this) {
                      while (flagWaitRemoveCards && !terminate) {
                           try
                           {wait();}
                           catch (InterruptedException e) {
                            // TODO: handle exception
                           }
                       }
                 }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
            aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        playerThread.interrupt();
        if(!human)
            aiThread.interrupt();
        try {
            playerThread.join();
        } catch (InterruptedException e) {
            // TODO: handle exception
        }
    }


    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(keyPress.size() <= setSize && penalty == 0 && !flagRemoveCards){
            keyPress.add(slot);
            synchronized (this){notifyAll();}
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        this.score++;
        env.ui.setScore(id, score);
        //long startTime = System.currentTimeMillis();
        long finishTime = System.currentTimeMillis() + pointFreeze;
        while(System.currentTimeMillis() <= finishTime){
            //long elapsedTime = System.currentTimeMillis() - startTime;
            //long elapsedSeconds = elapsedTime / 1000;
            //int timer = (int)((pointFreeze/1000 - elapsedSeconds) * 1000);
            env.ui.setFreeze(id, finishTime - System.currentTimeMillis() + 1000);
            if(pointFreeze > 1000) {
                long sleepTime = pointFreeze % 1000;
                goToSleep(sleepTime);
            }
            else
                goToSleep(pointFreeze);

        }
        env.ui.setFreeze(id, 0);

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        //long startTime = System.currentTimeMillis();
        long finishTime = System.currentTimeMillis() + penaltyFreeze;
        while(System.currentTimeMillis() <= finishTime){
            //long elapsedTime = System.currentTimeMillis() - startTime;
            //long elapsedSeconds = elapsedTime / 1000;
            //int timer = (int)((penaltyFreeze / 1000 - elapsedSeconds) * 1000);
            env.ui.setFreeze(id , finishTime - System.currentTimeMillis() +1000);
            if(penaltyFreeze > 1000) {
                Long sleepTime = penalty % 1000;
                goToSleep(sleepTime);
            }
            else
                goToSleep(penaltyFreeze);
        }
        env.ui.setFreeze(id , 0);
    }

    /**
     * player thread go to sleep for sleepTime sec
     */
    public void goToSleep(Long sleepTime){
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            //TODO: handle exception
        }
    }

    /**
     * get the player thread out of waiting mode
     */
    public void notifyPlayer(){
        synchronized(this){notifyAll();}
    }

    public int score() {
        return score;
    }

    /**
     * return int[] of the cards that currently in the slots of keyPressTracking
     */
    public void copyKeyPressTrackingToArrayOfCards() {
            int index = 0;
            Iterator<Integer> it = tokensTracking.iterator();
            while (it.hasNext()) {
                int slot = it.next();
                Object x = table.slotToCard[slot];
                if (x != null) {
                    int card = (int) x;
                    set[index] = card;
                    index++;
                }
            }


    }


    public void deleteKeyPressTracking(){
        tokensTracking.clear();
    }

    public void removeKeyPressTracking(int slot){
        tokensTracking.remove(slot);
    }

    public void setFlagRemoveCards(boolean x){
        flagRemoveCards = x;
    }

    public void setFlagWaitRemoveCards(boolean x){
        flagWaitRemoveCards = x;
    }

}


