package uk.ac.bris.cs.scotlandyard.ui.ai;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.ArrayList;
import java.util.List;

//I am going to break the advice of sticking with Board class here for a little part in ISMCTS
//Its just really painfull simulating the game with Board. Previous implementations kept corrupting
//the original Board for some reason I couldn't find.
//pretty sure it's my mistake and I just couldn't find it but it is what it is
//(pleeeeease dont deduct score I only used it for simulating)
//Burak Alican Kilinc

public class FakeGameStateGenerator {

    // reconstructPlayers : helper method safely copy existing players
    // @input board : current board
    // @input piece : player we want to copy
    // @input location : location of the player
    // @output Player : copy of the player
    private static Player reconstructPlayers(Board board, Piece piece, int location){
        Board.TicketBoard ticketBoard = board.getPlayerTickets(piece)
                .orElseThrow(() -> new RuntimeException("No tickets in board"));
        ImmutableMap.Builder<ScotlandYard.Ticket, Integer> ticketsBuilder = ImmutableMap.builder();

        for (ScotlandYard.Ticket ticketType : ScotlandYard.Ticket.values()) {
            int count = ticketBoard.getCount(ticketType);
            ticketsBuilder.put(ticketType, count);
        }
        return new Player(piece, ticketsBuilder.build(), location);

    }

    // buildFakeGameState : safe copy of a game state. This state will be used to simulate
    // various moves for the detectives
    // @input board : current board
    // @input guess : guessed location of mrX
    // @output GameState : generated GameState
    public static Board.GameState buildFakeGameState(Board board, int guess){
        Player fakeMrX = reconstructPlayers(board, Piece.MrX.MRX, guess);
        List<Player> detectives = new ArrayList<>();

        for (Piece piece : board.getPlayers()) {
            if (piece.isDetective()) {
                int loc = board.getDetectiveLocation((Piece.Detective) piece)
                        .orElseThrow(() -> new RuntimeException("No detective location in board"));
                detectives.add(reconstructPlayers(board, piece, loc));
            }
        }

        //yes I used my factory :D
        //proof of my knowledge in factory design pattern
        SandboxGameState factory = new SandboxGameState();
        ImmutableSet<Piece> que = board.getAvailableMoves().stream().map(Move::commencedBy).collect(ImmutableSet.toImmutableSet());
        ImmutableList<LogEntry> currentLog = board.getMrXTravelLog();

        return factory.createFakeState(board.getSetup(), que, currentLog, fakeMrX, detectives);
    }
}
