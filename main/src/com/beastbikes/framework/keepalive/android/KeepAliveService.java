package com.beastbikes.framework.keepalive.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;

@TargetApi(Build.VERSION_CODES.ECLAIR)
public final class KeepAliveService extends Service {

	private static final int MSG_START_DAEMON = 1;

	private static final int KEEP_ALIVE_SLEEP_SECONDS = 5;

	private static final long START_DAEMON_INTERVAL = 1000L;

	private static final String KEEPALIVE_EXE = "keepalive";

	private static final Logger logger = LoggerFactory
			.getLogger(KeepAliveService.class);

	private static String getUserHandleParam() {
		try {
			final Class<?> clazz = Class.forName("android.os.UserHandle");
			final Method m = clazz.getMethod("getUserId", int.class);
			if (m != null) {
				return "--user 0";
			}
		} catch (final Exception e) {
			logger.error(e.getMessage(), e);
		}

		return "";
	}

	private static int chmod(final String path, final int mode) {
		Process proc = null;
		final Locale locale = Locale.getDefault();
		final String cmd = String.format(locale, "chmod %d %s", mode, path);

		try {
			proc = Runtime.getRuntime().exec(cmd);
			return proc.waitFor();
		} catch (final Exception e) {
			return -1;
		} finally {
			if (null != proc) {
				proc.destroy();
			}
		}
	}

	private HandlerThread handlerThread;

	private Thread serverThread;

	private Handler handler;

	private int randomCode = -1;

	private LocalSocket ls = null;

	private LocalServerSocket lss = null;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		this.handlerThread = new HandlerThread("KeepAlive");
		this.handlerThread.setDaemon(true);
		this.handlerThread.setPriority(Thread.MIN_PRIORITY);
		this.handlerThread.start();

		this.handler = new Handler(this.handlerThread.getLooper(),
				new Handler.Callback() {

					@Override
					public boolean handleMessage(Message msg) {
						switch (msg.what) {
						case MSG_START_DAEMON:
							if (0 != startDaemon(genLssName(), randomCode)) {
								return handler
										.sendEmptyMessageDelayed(
												MSG_START_DAEMON,
												START_DAEMON_INTERVAL);
							}
							return true;
						}

						return false;
					}

				});

		this.serverThread = new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					// Startup Server to wait connection from native process
					while (ls == null) {
						try {
							if (lss == null) {
								lss = new LocalServerSocket(genLssName());
							}

							logger.trace("Connect waiting...");

							handler.sendEmptyMessage(MSG_START_DAEMON);
							ls = lss.accept();
							handler.removeMessages(MSG_START_DAEMON);

							logger.trace("Connect OK");
						} catch (final Exception e) {
							logger.error(e.getMessage(), e);

							if (lss != null) {
								try {
									lss.close();
								} catch (final IOException ex) {
								}
								lss = null;
							}

							logger.trace("Relocate lss name=%s\n", genLssName());

							if (ls != null) {
								try {
									ls.close();
								} catch (final IOException ex) {
								}
								ls = null;
							}
						}
					}

					// Communicate with native client to ensure the connection
					// is OK, otherwise, restart server immediately
					try {
						while (true) {
							if (-1 == ls.getInputStream().read()) {
								break;
							}
						}
					} catch (final Exception e) {
						logger.error(e.getMessage(), e);
					} finally {
						if (ls != null) {
							try {
								ls.close();
							} catch (final IOException e) {
							}
							ls = null;
						}

					}

					randomCode += new Random(System.currentTimeMillis())
							.nextInt(10) + 1;
				}
			}

		}, "KeepAliveServer");
		this.serverThread.setPriority(Thread.MIN_PRIORITY);
		this.serverThread.setDaemon(true);
		this.serverThread.start();
	}

	private boolean copyExecFile() {
		final File f = new File(getFilesDir(), KEEPALIVE_EXE);

		if (!f.exists()) {
			try {
				if (!f.createNewFile()) {
					return false;
				}
			} catch (final IOException e) {
				return false;
			}
		} else {
			if (!f.delete()) {
				return false;
			}
		}

		final byte[] buffer = new byte[4096];

		int n;
		InputStream is = null;
		FileOutputStream fos = null;

		try {
			is = getAssets().open(KEEPALIVE_EXE);
			fos = new FileOutputStream(f);

			while ((n = is.read(buffer)) > 0) {
				fos.write(buffer, 0, n);
			}

			fos.flush();

			return 0 == chmod(f.getAbsolutePath(), 700);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
				is = null;
			}

			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
				}
				fos = null;
			}
		}

		return false;
	}

	private int startDaemon(final String lssName, final int randomCode) {
		if (!copyExecFile()) {
			return -1;
		}

		final File exe = new File(getFilesDir(), KEEPALIVE_EXE);
		final ComponentName cn = new ComponentName(this, this.getClass());
		final String cmd = "am startservice -n " + cn.flattenToString() + " %s";
		final String exec = String.format(cmd, getUserHandleParam());
		final String[] params = { exe.getAbsolutePath(), lssName,
				String.valueOf(randomCode), exec,
				String.valueOf(KEEP_ALIVE_SLEEP_SECONDS), };

		Process p = null;
		try {
			p = Runtime.getRuntime().exec(params);
			return p.waitFor();
		} catch (final Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (p != null) {
				p.destroy();
			}
		}

		return -1;
	}

	private String genLssName() {
		return getPackageName() + ".keepalive."
				+ (System.currentTimeMillis() / 10000);
	}

}
