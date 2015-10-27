package com.synchronize.verify;

public class SynchronizedVerify {
	
	
	/**-------------------------------------包裹代码块------------------------------**/
	Object objA = new Object();
	Object objB = new Object();
	
	public void blockNormal(){ track("blockNormal");}
	public void blockStyle(){
		synchronized (objA) { track("blockStyle"); }
	}
	/**相同场景下对比**/
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
	
	/**-------------------------------------修饰方法------------------------------**/
	public void methodNormal(){ track("methodNormal");}
	public synchronized void methodStyle(){ track("methodStyle");}
	public synchronized void methodContrast(){ track("methodContrast");}/**相同场景下对比**/
	public synchronized static void methodStatic(){ track("methodStatic");}
	
	
	
	/**循环输出一段内容**/
	private static void track(String callerName){
		for(int i = 0;i < 3 ;i++)
			System.out.println(
					callerName+":"+i+"\t|"+
					Thread.currentThread().getName());
			
			Thread.yield();
	}
}
