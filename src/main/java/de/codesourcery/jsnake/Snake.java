package de.codesourcery.jsnake;

import java.util.ArrayList;
import java.util.List;

public class Snake
{
    public final List<BodyPart> snake = new ArrayList<>();

    public record BodyPart(int x, int y, Direction direction) {
    }

    /*
                    snake.add( s1 ); /// tail
                snake.add( s2 ); /// head
     */
    public BodyPart head() {
        return snake.get( snake.size() - 1 );
    }

    public boolean isBodyPartAt(int newX, int newY)
    {
        return snake.stream().anyMatch( s -> s.x() == newX && s.y() == newY );
    }

    public BodyPart get(int idx) {
        return snake.get( idx );
    }

    public void clear() {
        snake.clear();
    }

    public int size() {
        return snake.size();
    }

    public void add(BodyPart part) {
        snake.add( part );
    }

    public BodyPart tail() {
        return snake.get( 0 );
    }

    public void removeTailBodyPart() {
        snake.removeFirst();
    }
}
