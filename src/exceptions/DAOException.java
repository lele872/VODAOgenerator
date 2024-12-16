// Decompiled by Jad v1.5.8e2. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://kpdus.tripod.com/jad.html
// Decompiler options: packimports(3)
// Source File Name:   DAOException.java

package exceptions;


public class DAOException extends Exception
{

    public DAOException()
    {
        internalException = null;
    }

    public DAOException(Exception e)
    {
        internalException = null;
        internalException = e;
    }

    public DAOException(String s, Exception e)
    {
        super(s);
        internalException = null;
        internalException = e;
    }

    public DAOException(String s)
    {
        super(s);
        internalException = null;
    }

    public Exception getInternalException()
    {
        return internalException;
    }

    private Exception internalException;
}
