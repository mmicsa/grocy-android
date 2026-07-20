package com.sunmi.trans;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * TransBean is used to package data for transaction printing.
 * It implements Parcelable to allow communication between your app 
 * and the Sunmi Printer Service.
 */
public class TransBean implements Parcelable {
    private byte type = 0;        // Type of data (e.g., text, raw bytes)
    private String text = "";     // Text content if applicable
    private byte[] data = null;   // Binary data/commands
    private int datalength = 0;   // Length of the data array

    // Default Constructor
    public TransBean() {
        type = 0;
        data = null;
        text = "";
        datalength = 0;
    }

    // Parametrized Constructor
    public TransBean(byte type, String text, byte[] data) {
        this.type = type;
        this.text = text;
        if (data != null) {
            this.datalength = data.length;
            this.data = new byte[datalength];
            System.arraycopy(data, 0, this.data, 0, datalength);
        }
    }

    // Getters and Setters
    public byte getType() { return type; }
    public void setType(byte type) { this.type = type; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) {
        if (data != null) {
            this.datalength = data.length;
            this.data = new byte[datalength];
            System.arraycopy(data, 0, this.data, 0, datalength);
        } else {
            this.data = null;
            this.datalength = 0;
        }
    }

    // --- Parcelable Implementation ---

    protected TransBean(Parcel source) {
        this.type = source.readByte();
        this.datalength = source.readInt();
        this.text = source.readString();
        if (datalength > 0) {
            this.data = new byte[datalength];
            source.readByteArray(this.data);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(type);
        dest.writeInt(datalength);
        dest.writeString(text);
        if (data != null) {
            dest.writeByteArray(data);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<TransBean> CREATOR = new Parcelable.Creator<TransBean>() {
        @Override
        public TransBean createFromParcel(Parcel source) {
            return new TransBean(source);
        }

        @Override
        public TransBean[] newArray(int size) {
            return new TransBean[size];
        }
    };
}
