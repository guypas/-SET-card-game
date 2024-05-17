package bguspl.set.ex;

import bguspl.set.Env;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;
    /**
     * current number of sets found
     */
    private static int numberOfSets = 0;

    /**
     * max number of sets in the deck
     */
    private final static int maxNumberOfSets = 27;

    /**
     * keeps the players that sent the dealer set to check
     */
    public final ConcurrentLinkedQueue<Player> playersQueue;

    /**
     * The thread representing the dealer
     */
    public Thread dealerThread;

    /**
     * keep the number 3
     */
    private final int setSize;

    /**
     * keeps the sleep time for the function sleepUntilWoken.
     */
    private long sleepTime;

    /**
     * keeps boolean for the function updateTimerDisplay
     */
    private  boolean reset;

    /**
     * keeps env.config.turnTimeoutMillis;
     */
    private final long turnTimeOutMillis;

    /**
     * env.config.turnTimeoutWarningMillis;
     */
    private final long turnTimeOutWarningMillis;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * keep track of the tokens that are on the greed
     */
    public final ConcurrentLinkedQueue<Integer>[] tokens;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;
        playersQueue = new ConcurrentLinkedQueue<>();
        setSize = 3;
        turnTimeOutMillis = env.config.turnTimeoutMillis;
        turnTimeOutWarningMillis = env.config.turnTimeoutWarningMillis;
        tokens = new ConcurrentLinkedQueue [env.config.tableSize];
        for(int i = 0; i < tokens.length; i++)
            tokens[i] = new ConcurrentLinkedQueue<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for(int i = 0; i<players.length; i++){
            Thread playerThread = new Thread(players[i], "player"+" "+i);
            playerThread.start();
        }
        shuffleDeck();
        placeCardsOnTable();
        while (!terminate) {
            updateTimerDisplay(true);
            enablePlayers();
            timerLoop();
            blockPlayers();
            removeAllCardsFromTable();
        }
        announceWinners();
        try {
            Thread.sleep(env.config.endGamePauseMillies);
        } catch (Exception e) {
            // TODO: handle exception
        }
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(reset);
            if(checkSet()){
                reset = true;
            }

        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
        terminate = true;
        dealerThread.interrupt();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    public void removeAllCardsFromTable() {
            Integer[] slotToCard = table.slotToCard;
            int slotToCardLength = slotToCard.length;
            for (int i = 0; i < slotToCardLength; i++) {
                while (!tokens[i].isEmpty()) {
                    table.removeToken(tokens[i].remove(), i);
                }
                if (slotToCard[i] != null) {
                    deck.add(slotToCard[i]);
                    table.removeCard(i);
                }
            }
            for (Player player : players) {
                player.deleteKeyPressTracking();
            }

            if (!shouldFinish()) {
                shuffleDeck();
                placeCardsOnTable();
            } else {
                terminate = true;
            }

    }


    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(int [] set) {
            for (int card : set) {
                int slot = table.cardToSlot[card];
                while (!tokens[slot].isEmpty()) {
                    int id = tokens[slot].remove();
                    players[id].removeKeyPressTracking(slot);
                    table.removeToken(id, slot);
                }
                table.removeCard(slot);

            }
            placeCardsOnTable();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Integer[] slotToCard = table.slotToCard;
        for (int i = 0; i < slotToCard.length; i++) {
            if (slotToCard[i] == null) {
                if (!deck.isEmpty()) {
                    Integer card = deck.remove(0);
                    table.placeCard(card, i);
                }
            }
        }

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized(this){
            try {
                wait(sleepTime);
            } catch (Exception e) {
                // TODO: handle exception
            }
        }
    }

    /**
     * shuffle the deck of cards
     */
    private void shuffleDeck(){
        Collections.shuffle(deck);
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset){
        if(reset){
            env.ui.setCountdown(turnTimeOutMillis, false);
            reshuffleTime = System.currentTimeMillis() + turnTimeOutMillis +999;
            this.reset = false;
            sleepTime = 20;
        }
        else if(reshuffleTime - System.currentTimeMillis() > 0 && reshuffleTime - System.currentTimeMillis() < turnTimeOutWarningMillis){
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis() , true);
            sleepTime = 1;
        } else{
            env.ui.setCountdown( reshuffleTime - System.currentTimeMillis() , false);
        }
    }

    /**
     * check if the cards are legal set and perform other actions
     *
     */
    public boolean checkSet(){
        if(playersQueue.isEmpty())
            return false;
        boolean ans = false;
        Player player = playersQueue.remove();
        int [] set = player.set;
        Queue<Integer> tokensTracking = player.tokensTracking;
        boolean sameCardsInSlots = sameCardsInSlots(set , tokensTracking);
        boolean isSet = env.util.testSet(set);
        if (isSet && sameCardsInSlots){
            numberOfSets++;
            blockPlayers();
            removeCardsFromTable(set);
            if(numberOfSets < maxNumberOfSets)
                enablePlayers();
            player.penalty = 1;
            ans = true;
        } else if (!isSet && sameCardsInSlots){
            player.penalty = 3;
        } else{
            player.penalty = 0;
        }
        player.notifyPlayer();
        return ans;
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = maxScore();
        int numberOfWinners = countNumberOfWinners(maxScore);
        int [] winners = new int[numberOfWinners];
        int arrayIndex = 0;
        for (Player player : players)
            if(player.score() == maxScore){
                winners[arrayIndex] = player.id;
                arrayIndex++;
            }
        env.ui.announceWinner(winners);
    }

    /**
     * return the number of players with the max score
     */
    public int countNumberOfWinners(int maxScore){
        int counter = 0;
        for (Player player: players)
            if(player.score() == maxScore)
                counter++;
        return counter;
    }

    /**
     * return the max score of the players
     */
    private int maxScore(){
        int maxScore = -1;
        for (Player player: players){
            int currentScore = player.score();
            if(currentScore > maxScore)
                maxScore = currentScore;
        }
        return maxScore;
    }

    /**
     * get the dealer thread out of waiting mode
     */
    public void notifyDealer(){
        synchronized(this){
            notify();
        }
    }

    /**
     * helping method that help the dealer keep track of the tokens on the table
     */
    public void deleteToken(int  id , int slot){
        tokens[slot].remove(id);
    }

    /**
     * helping method that help the dealer keep track of the tokens on the table
     */
    public void addToken(int id , int slot){
        tokens[slot].add(id);
    }

    /**
     * block all the players to put tokens on the greed
     */
    private void blockPlayers(){
        for (Player player: players) {
            player.setFlagRemoveCards(true);
            player.setFlagWaitRemoveCards(true);
        }
    }

    /**
     * cancel blockPlayers
     */
    private void enablePlayers(){
        for (Player player: players) {
            player.setFlagRemoveCards(false);
            player.setFlagWaitRemoveCards(false);
            player.notifyPlayer();
        }
    }

    /**
     * add player to playersQueue
     */
    public void addPlayer(Player p){
        playersQueue.add(p);
    }

    /**
     * check if the card of set are still on the table
     */
    public boolean sameCardsInSlots(int [] set , Queue<Integer>b){
        boolean ans = true;
        for(int i = 0; i < setSize && ans; i++){
            int card = set[i];
            Iterator<Integer> it = b.iterator();
            ans = false;
            while(it.hasNext() && !ans){
                int slot = it.next();
                if(table.slotToCard[slot] == card)
                    ans = true;
            }
        }
        return ans;
    }


    public Player[] getPlayers() {
        return players;
    }
}
