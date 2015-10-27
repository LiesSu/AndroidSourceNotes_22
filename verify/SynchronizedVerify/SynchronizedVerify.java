package com.synchronize.verify;

public class SynchronizedVerify {
	
	
	/**-------------------------------------���������------------------------------**/
	Object objA = new Object();
	Object objB = new Object();
	
	public void blockNormal(){ track("blockNormal");}
	public void blockStyle(){
		synchronized (objA) { track("blockStyle"); }
	}
	/**��ͬ�����¶Ա�**/
	public void blockContrast( ){
		synchronized (objA) { track("blockContrast");}
	}
	public void blockDiffObj(){
		synchronized (objB) { track("blockDiffObj");}
	}
	public void blockOneself(){
		synchronized (this) { track("blockOneself");}
	}

	public void blockClass(){
		synchronized (SynchronizedVerify.class) { track("blockClass");}
	}
	
	/**-------------------------------------���η���------------------------------**/
	public void methodNormal(){ track("methodNormal");}
	public synchronized void methodStyle(){ track("methodStyle");}
	public synchronized void methodContrast(){ track("methodContrast");}/**��ͬ�����¶Ա�**/
	public synchronized static void methodStatic(){ track("methodStatic");}
	
	
	
	/**ѭ�����һ������**/
	private static void track(String callerName){
		for(int i = 0;i < 3 ;i++)
			System.out.println(
					callerName+":"+i+"\t|"+
					Thread.currentThread().getName());
			
			Thread.yield();
	}
}
