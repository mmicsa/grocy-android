package woyou.aidlservice.jiuiv5;

/**
 * Printer execution callback
 */
interface ICallback {
	/**
	 * Return result from printer
	 * @param success: whether the execution was successful
	 */
	oneway void onRunResult(boolean success);

	/**
	 * Return result with message
	 */
	oneway void onReturnString(String result);

	/**
	 * Return error code and message
	 */
	oneway void onRaiseException(int code, String msg);

	/**
	 * Return print result code
	 */
	oneway void onPrintResult(int code, String msg);
}
