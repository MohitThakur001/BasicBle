package com.apogee.basicble.SQlite;

/**
 * Model class for table items
 * Getter and setter for table items
 */
public class Model {

    private int id;


    private String getResp;

    private String date;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }


    public String getGetResp() {
        return getResp;
    }

    public void setGetResp(String getResp) {
        this.getResp = getResp;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

}
