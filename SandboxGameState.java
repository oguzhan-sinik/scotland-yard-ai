package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nonnull;
import org.checkerframework.checker.nullness.qual.NonNull;
import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.*;

public class SandboxGameState implements ScotlandYard.Factory<Board.GameState> {
    private final class MyGameState implements Board.GameState {
        private GameSetup setup;
        private ImmutableSet<Piece> remaining;
        private ImmutableList<LogEntry> log;
        private Player mrX;
        private List<Player> detectives;
        private ImmutableSet<Move> moves;
        private ImmutableSet<Piece> winner;

        private MyGameState(
                final GameSetup setup,
                final ImmutableSet<Piece> remaining,
                final ImmutableList<LogEntry> log,
                final Player mrX,
                final List<Player> detectives){

            this.setup = setup;
            this.remaining = remaining;
            this.log = log;
            this.mrX = mrX;
            this.detectives = detectives;

            ImmutableSet.Builder<Move> builder = ImmutableSet.builder();
            for (Piece piece : remaining) {
                if (piece.isMrX()) {
                    builder.addAll(makeSingleMoves(setup, detectives, mrX, mrX.location()));

                    if (mrX.has(ScotlandYard.Ticket.DOUBLE) && (setup.moves.size() - log.size()) >= 2){
                        builder.addAll(makeDoubleMoves(setup, detectives, mrX, mrX.location()));
                    }
                } else {
                    for (Player detective : detectives) {
                        if (detective.piece().equals(piece)) {
                            builder.addAll(makeSingleMoves(setup, detectives, detective, detective.location()));
                            break;
                        }
                    }
                }
            }
            this.moves = builder.build();

            if(setup.moves.isEmpty()) throw new IllegalArgumentException("Moves is empty!");

        }

        @Override @Nonnull
        public GameSetup getSetup(){ return setup; }

        @Override
        public @NonNull ImmutableSet<Piece> getPlayers() {
            ImmutableSet.Builder<Piece> builder = ImmutableSet.builder();
            for (Player detective : detectives) {
                builder.add(detective.piece());
            }
            builder.add(mrX.piece());
            return builder.build();
        }

        @Override @Nonnull public ImmutableList<LogEntry> getMrXTravelLog(){ return log; }

        @Override
        public @NonNull ImmutableSet<Piece> getWinner() {
            ImmutableSet.Builder<Piece> detectivePieces = ImmutableSet.builder();
            for (Player detective : detectives) {
                detectivePieces.add(detective.piece());
            }

            ImmutableSet<Piece> allDetectives = detectivePieces.build();

            for (Player detective : detectives) {
                if (detective.location() == mrX.location()) {
                    return allDetectives;
                }
            }

            if (log.size() == setup.moves.size() && remaining.contains(mrX.piece())) {
                return ImmutableSet.of(mrX.piece());
            }

            if (remaining.contains(mrX.piece()) && moves.isEmpty()) {
                return allDetectives;
            }

            boolean detectiveCanMove = false;
            for (Player detective : detectives) {
                if(!makeSingleMoves(setup, detectives, detective, detective.location()).isEmpty()) {
                    detectiveCanMove = true;
                    break;
                }
            }

            if (!detectiveCanMove) {
                return ImmutableSet.of(mrX.piece());
            }

            return ImmutableSet.of();
        }

        @Override
        public @NonNull ImmutableSet<Move> getAvailableMoves() {
            if (!getWinner().isEmpty()) return ImmutableSet.of();
            return moves;
        }

        @Override @Nonnull public Optional<Integer> getDetectiveLocation(Piece.Detective detective){

            for (Player p : detectives){
                if (p.piece().equals(detective)) return Optional.of(p.location());
            }
            return Optional.empty();
        }

        @Override
        public @NonNull Optional<TicketBoard> getPlayerTickets(Piece piece) {
            if (piece.equals(mrX.piece())) {
                return Optional.of(ticket -> mrX.tickets().getOrDefault(ticket, 0));
            }

            for (Player detective : detectives) {
                if (detective.piece().equals(piece)) {
                    return Optional.of(ticket -> detective.tickets().getOrDefault(ticket, 0));
                }
            }

            return Optional.empty();
        }

        @Nonnull public GameState advance(Move move){
            if(!moves.contains(move)) throw new IllegalArgumentException("Illegal move: "+move);

            return move.accept(new Move.Visitor<GameState>(){
                @Override
                public GameState visit(Move.SingleMove move) {
                    if(move.commencedBy().isMrX()){
                        Player newMrx = mrX.use(move.ticket).at(move.destination);
                        boolean isReveal = setup.moves.get(log.size());
                        LogEntry newLog = isReveal ? LogEntry.reveal(move.ticket, move.destination) : LogEntry.hidden(move.ticket);
                        List<LogEntry> updatedLogs = new ArrayList<>(log);
                        updatedLogs.add(newLog);

                        Set<Piece> nextRemaining = new HashSet<>();
                        for(Player p : detectives){
                            if (!makeSingleMoves(setup, detectives, p, p.location()).isEmpty()) {
                                nextRemaining.add(p.piece());
                            }
                        }
                        if (nextRemaining.isEmpty()) nextRemaining.add(newMrx.piece());

                        return new MyGameState(setup, ImmutableSet.copyOf(nextRemaining), ImmutableList.copyOf(updatedLogs), newMrx, detectives);
                    } else {
                        Player activeDetective = null;
                        for(Player p : detectives){
                            if (p.piece().equals(move.commencedBy())){
                                activeDetective = p;
                                break;
                            }
                        }

                        Player newDetective = activeDetective.use(move.ticket).at(move.destination);
                        Player newMrX = mrX.give(move.ticket);
                        List<Player> updatedDetectives = new ArrayList<>();
                        for (Player p : detectives) {
                            if (p.piece().equals(newDetective.piece())) updatedDetectives.add(newDetective);
                            else updatedDetectives.add(p);
                        }

                        Set<Piece> nextRemaining = new HashSet<>(remaining);
                        nextRemaining.remove(newDetective.piece());

                        Set<Piece> checkRemaining = new HashSet<>();
                        for (Piece piece : nextRemaining) {
                            for (Player p : updatedDetectives) {
                                if (p.piece().equals(piece)) {
                                    if (!makeSingleMoves(setup, updatedDetectives, p, p.location()).isEmpty()) {
                                        checkRemaining.add(p.piece());
                                    }
                                    break;
                                }
                            }
                        }
                        if (checkRemaining.isEmpty()) checkRemaining.add(newMrX.piece());

                        return new MyGameState(setup, ImmutableSet.copyOf(checkRemaining), log, newMrX, updatedDetectives);
                    }
                }

                @Override
                public GameState visit(Move.DoubleMove move) {
                    Player newMrX = mrX.use(move.tickets()).at(move.destination2);
                    List<LogEntry> updatedLogs = new ArrayList<>(log);

                    boolean isFirstReveal = setup.moves.get(log.size());
                    updatedLogs.add(isFirstReveal ? LogEntry.reveal(move.ticket1, move.destination1) : LogEntry.hidden(move.ticket1));
                    boolean isSecondReveal = setup.moves.get(updatedLogs.size());
                    updatedLogs.add(isSecondReveal ? LogEntry.reveal(move.ticket2, move.destination2) : LogEntry.hidden(move.ticket2));

                    Set<Piece> nextRemaining = new HashSet<>();
                    for(Player p : detectives) {
                        if (!makeSingleMoves(setup, detectives, p, p.location()).isEmpty()) {
                            nextRemaining.add(p.piece());
                        }
                    }
                    if (nextRemaining.isEmpty()) nextRemaining.add(newMrX.piece());
                    return new MyGameState(setup, ImmutableSet.copyOf(nextRemaining), ImmutableList.copyOf(updatedLogs), newMrX, detectives);

                }
            });


        }

        private static Set<Move> makeSingleMoves(GameSetup setup, List<Player> detectives, Player player, int source){

            // TODO create an empty collection of some sort, say, HashSet, to store all the SingleMove we generate
            HashSet<Move> moves = new HashSet<>();

            for(int destination : setup.graph.adjacentNodes(source)) {
                // TODO find out if destination is occupied by a detective
                //  if the location is occupied, don't add to the collection of moves to return
                boolean isOccupied = false;
                for(Player p : detectives){
                    if(p.location() == destination){isOccupied = true; break;}
                }
                if(isOccupied){continue;}

                for(ScotlandYard.Transport t : setup.graph.edgeValueOrDefault(source, destination, ImmutableSet.of()) ) {
                    // TODO find out if the player has the required tickets
                    //  if it does, construct a SingleMove and add it the collection of moves to return
                    if(player.has(t.requiredTicket())){
                        Move.SingleMove singleMove = new Move.SingleMove(player.piece(), source, t.requiredTicket(), destination);
                        moves.add(singleMove);
                    }
                }

                // TODO consider the rules of secret moves here
                //  add moves to the destination via a secret ticket if there are any left with the player
                if(player.has(ScotlandYard.Ticket.SECRET)){
                    moves.add(new Move.SingleMove(player.piece(), source, ScotlandYard.Ticket.SECRET, destination));
                }
            }

            // TODO return the collection of moves
            return moves;
        }

        private static Set<Move> makeDoubleMoves(GameSetup setup, List<Player> detectives, Player player, int source){
            HashSet<Move> moves = new HashSet<>();
            Set<Move> first = makeSingleMoves(setup, detectives, player, source);

            for (Move firstMove : first) {
                Move.SingleMove singleMove = (Move.SingleMove) firstMove;
                for (int destination : setup.graph.adjacentNodes(singleMove.destination)) {
                    boolean isOccupied = false;
                    for(Player p : detectives){
                        if(p.location() == destination){isOccupied = true; break;}
                    }
                    if(isOccupied){continue;}

                    for(ScotlandYard.Transport t : setup.graph.edgeValueOrDefault(singleMove.destination, destination, ImmutableSet.of()) ) {
                        // TODO find out if the player has the required tickets
                        //  if it does, construct a SingleMove and add it the collection of moves to return
                        boolean enoughTicket = singleMove.ticket == t.requiredTicket()
                                ? player.hasAtLeast(t.requiredTicket(), 2)
                                : player.has(t.requiredTicket());
                        if(enoughTicket) {
                            Move.DoubleMove doubleMove = new Move.DoubleMove(player.piece(), source, singleMove.ticket, singleMove.destination, t.requiredTicket(), destination);
                            moves.add(doubleMove);
                        }
                    }

                    if(player.has(ScotlandYard.Ticket.SECRET)){
                        moves.add(new Move.DoubleMove(player.piece(), source, singleMove.ticket, singleMove.destination,ScotlandYard.Ticket.SECRET, destination));
                    }
                }
            }

            return moves;
        }


    }

    @Nonnull @Override public Board.GameState build(
            GameSetup setup,
            Player mrX,
            ImmutableList<Player> detectives) {
        // TODO
        Set<Piece> seenPieces = new HashSet<>();
        Set<Integer> seenLocations = new HashSet<>();

        for (Player detective : detectives) {
            if (detective.has(ScotlandYard.Ticket.SECRET) || detective.has(ScotlandYard.Ticket.DOUBLE)) {
                throw new IllegalArgumentException("Illegal tickets in detectives: " + detective);
            }

            if (seenPieces.contains(detective.piece())) {
                throw new IllegalArgumentException("Duplicate detectives exist: " + detective);
            }
            seenPieces.add(detective.piece());

            if (seenLocations.contains(detective.location())) {
                throw new IllegalArgumentException("Duplicate locations exist: " + detective);
            }
            seenLocations.add(detective.location());
        }

        ImmutableSet<Piece> initialRemanining = ImmutableSet.of(mrX.piece());
        ImmutableList<LogEntry> initialLog =  ImmutableList.of();

        return new MyGameState(setup, initialRemanining, initialLog, mrX, detectives);

    }

    // Yeah I had to copy paste the entire factory code for just this method
    // The build method creates a game from scratch. Naturally, MrX starts playing
    // this method takes in queue for the turns
    // Again, very sorry for this. Please dont punish me ;(
    public Board.GameState createFakeState(
            GameSetup setup,
            ImmutableSet<Piece> remaining,
            ImmutableList<LogEntry> log,
            Player mrX,
            List<Player> detectives) {

        return new MyGameState(setup, remaining, log, mrX, detectives);
    }



}
