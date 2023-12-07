package de.codesourcery.jsnake;

public enum Direction
{
    UP( 0, -1 ), DOWN( 0, 1 ), LEFT( -1, 0 ), RIGHT( 1, 0 );

    public final int dx, dy;

    Direction(int dx, int dy)
    {
        this.dx = dx;
        this.dy = dy;
    }

    public Direction reversed()
    {
        return switch( this )
        {
            case UP -> Direction.DOWN;
            case DOWN -> Direction.UP;
            case LEFT -> Direction.RIGHT;
            case RIGHT -> Direction.LEFT;
        };
    }
}
