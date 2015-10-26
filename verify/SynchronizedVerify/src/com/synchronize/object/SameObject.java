/**
 * 
 */
package com.synchronize.object;

/**
 * a.����������synchronized (this)
 * 1.so.fun1()+so.fun2()������
 * 2.so.fun1()+so.fun1()������
 * 
 * b.fun1��synchronized (this)
 * 1.so.fun1()+so.fun2()��������
 * 2.so.fun1()+so.fun1()������
 * 
 * c.fun1��synchronized (this)��fun2������synchronized
 * 1.so.fun1()+so.fun2()������
 * 2.so.fun1()+so.fun1()������
 * 
 * �Ա�a.b.c��ʵ��˵����
 * ֻҪһ���߳̽�����so,�����̱߳㲻���ٽ���so���κ�һ��ͬ�����루�����Խ����ͬ�����룩��
 */
public class SameObject {
	
	public static void main(String[] args){
		final SameObject so = new SameObject();
		new Thread(){
			public void run() {
				so.fun2();
			}
			
		}.start();
		new Thread(){
			public void run() {
				so.fun2();
			}
			
		}.start();
	}
	
	
	public void fun1(){
		System.out.println(Thread.currentThread().getName()+":Start fun1");	
		
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun1 Synchronized!");
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public  void fun2(){
		System.out.println(Thread.currentThread().getName()+":Start fun2");	
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun2 Synchronized!");	
				

				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
}
