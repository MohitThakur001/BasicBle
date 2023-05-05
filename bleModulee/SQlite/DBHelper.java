package com.apogee.basicble.SQlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;


import java.util.ArrayList;
import java.util.List;

public class DBHelper extends SQLiteOpenHelper {

    /**
     * @param context
     * constructor
     */
    public DBHelper(Context context) {
        super(context, RequiredParams.DB_NAME, null, RequiredParams.DB_VERSION);

    }

    /**
     * SQL Query
     */
    String respTable = "CREATE TABLE " + RequiredParams.TABLE_NAME + "("
            + RequiredParams.KEY_ID + " INTEGER PRIMARY KEY, "
            + RequiredParams.KEY_DATE + " TEXT, "
            + RequiredParams.KEY_SERVER_RESPONSE + " TEXT" + ")";


    /**
     * Code is executing the query we defined before
     * @param db The database.
     */

    @Override
    public void onCreate(SQLiteDatabase db) {

        db.execSQL(respTable);
    }

    /**
     * This method is used for inserting data into the table
     * @param model
     */
    public void addResult(Model model) {

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RequiredParams.KEY_SERVER_RESPONSE, model.getGetResp());
        values.put(RequiredParams.KEY_DATE, model.getDate());

        db.insert(RequiredParams.TABLE_NAME, null, values);
        Log.d("sdasf", "successfully inserted");
        db.close();

    }

    /**
     * This method is used to get data from the table
     *
     * @return
     */

    public List<Model> getAllResult() {//fun to get data from table
        List<Model> modelList = new ArrayList<>(); //creating array list to store data into list
        SQLiteDatabase db = this.getReadableDatabase(); //creating db obj to read data
        String select = "SELECT * FROM " + RequiredParams.TABLE_NAME; //generating query to select db

        //cursor is used to navigate into db table
        Cursor cursor = db.rawQuery(select, null); //creating cursor obj and passing query into cursor
        if (cursor.moveToFirst()) {
            do { //using do while loop because first it will intialise then condition will check
                Model model = new Model(); //creating obj of contact because we need to set data
                model.setId(Integer.parseInt(cursor.getString(0))); //get values from cursor and setting data into contact model
                model.setGetResp(cursor.getString(1));

                model.setDate(cursor.getString(2));

                modelList.add(model); // in last adding all contact into list

            } while (cursor.moveToNext()); //checking condition here
        }


        return modelList;
    }

    /**
     *
     * Called db needs to be upgraded
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // this method is called to check if the table exists already.
        db.execSQL("DROP TABLE IF EXISTS " + RequiredParams.TABLE_NAME);
        onCreate(db);


}

//    public void deleteResult(int id) { //we have taken id here because we need a parameter inside this
//        SQLiteDatabase db = this.getWritableDatabase();
//        db.delete(RequiredParams.TABLE_NAME, RequiredParams.KEY_ID + "=?", new String[]{String.valueOf(id)});
//        Log.d("db_check", "successfully deleted");
//        db.close(); //this is mandatory
//    }

}
