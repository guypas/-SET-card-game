package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    Player player;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Dealer dealer;
    @Mock
    private Env env;
    @Mock
    private Logger logger;
    void assertInvariants() {
        assertTrue(player.id >= 0);
        assertTrue(player.score() >= 0);
    }

    @BeforeEach
    void setUp() {
        // purposely do not find the configuration files (use defaults here).
        env = new Env(logger, new Config(logger, (String) null), ui, util);
        player = new Player(env, dealer, table, 0, false);
        Player[] players = {player};
        Integer [] slotToCard = new Integer[env.config.tableSize];
        Integer [] cardToSlot = new Integer[env.config.deckSize];
        table = new Table(env, slotToCard, cardToSlot);
        dealer = new Dealer(env, table, players);
        assertInvariants();
    }

    @AfterEach
    void tearDown() {
        assertInvariants();
    }
    @Test
    void dealerConstructor(){
        assertEquals(1, dealer.getPlayers().length);
        assertEquals(0, dealer.playersQueue.size());
        assertEquals(env.config.tableSize, dealer.tokens.length);
    }

    @Test
    void countNumberOfWinners(){
        assertEquals(1, dealer.countNumberOfWinners(0));
        assertEquals(0, dealer.countNumberOfWinners(1));
        player.score++;
        assertEquals(1, dealer.countNumberOfWinners(1));
        assertNotEquals(0, dealer.countNumberOfWinners(1));
    }

    @Test
    /**
     * @post table.slotToCard[i] = null for every i from 0 to 11
     */
    void removeAllCardsFromTable(){
        table.placeCard(3, 0);
        table.placeCard(4, 1);
        assertEquals(3, table.slotToCard[0]);
        assertEquals(4, table.slotToCard[1]);
        dealer.removeAllCardsFromTable();
        assertEquals(null, table.slotToCard[0]);
        assertEquals(null, table.slotToCard[1]);
        
    }
}