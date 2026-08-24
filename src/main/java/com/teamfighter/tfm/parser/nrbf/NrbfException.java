package com.teamfighter.tfm.parser.nrbf;

/** NRBF 스트림을 읽다 만난 오류. 조용히 넘기지 않는다. */
public class NrbfException extends RuntimeException {

    public NrbfException(String message) {
        super(message);
    }

    public NrbfException(String message, Throwable cause) {
        super(message, cause);
    }
}
