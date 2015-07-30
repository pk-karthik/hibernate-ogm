/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.redis.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.ogm.cfg.spi.Hosts;
import org.hibernate.ogm.datastore.redis.RedisDialect;
import org.hibernate.ogm.datastore.redis.logging.impl.Log;
import org.hibernate.ogm.datastore.redis.logging.impl.LoggerFactory;
import org.hibernate.ogm.datastore.spi.BaseDatastoreProvider;
import org.hibernate.ogm.dialect.spi.GridDialect;
import org.hibernate.ogm.util.configurationreader.spi.ConfigurationPropertyReader;
import org.hibernate.service.spi.Configurable;
import org.hibernate.service.spi.ServiceRegistryAwareService;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.service.spi.Startable;
import org.hibernate.service.spi.Stoppable;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.codec.RedisCodec;

import static com.lambdaworks.redis.RedisURI.Builder.redis;

/**
 * Provides access to Redis
 * it can be taken via JNDI or started by this ServiceProvider; in this case it will also
 * be stopped when no longer needed.
 *
 * @author Mark Paluch
 */
public class RedisDatastoreProvider extends BaseDatastoreProvider implements Startable, Stoppable,
		ServiceRegistryAwareService, Configurable {

	private static final Log log = LoggerFactory.getLogger();
	private static final RedisClient redisClient;

	// RedisClient.connect accepting codec and RedisURI is private
	private static final Method CONNECT_CODEC_URI;
	private static final ByteArrayCodec CODEC = new ByteArrayCodec();

	static {
		redisClient = new RedisClient();
		Runtime.getRuntime().addShutdownHook(
				new Thread( "RedisClient-Shutdown-Hook" ) {
					@Override
					public void run() {
						redisClient.shutdown( 100, 100, TimeUnit.MILLISECONDS );
					}
				}
		);

		try {
			CONNECT_CODEC_URI = RedisClient.class.getDeclaredMethod( "connect", RedisCodec.class, RedisURI.class );
			CONNECT_CODEC_URI.setAccessible( true );
		}
		catch (Exception e) {
			throw new IllegalStateException(
					"RedisClient does not have the private connect(RedisCodec, RedisURI) method. Beat up the developer.",
					e
			);
		}
	}


	private ServiceRegistryImplementor serviceRegistry;

	private RedisConfiguration config;
	private RedisConnection<byte[], byte[]> connection;


	@Override
	public Class<? extends GridDialect> getDefaultDialect() {
		return RedisDialect.class;
	}

	@Override
	public void configure(Map configurationValues) {
		ClassLoaderService classLoaderService = serviceRegistry.getService( ClassLoaderService.class );
		ConfigurationPropertyReader propertyReader = new ConfigurationPropertyReader(
				configurationValues,
				classLoaderService
		);

		try {
			this.config = new RedisConfiguration( propertyReader );
		}
		catch (Exception e) {
			// Wrap Exception in a ServiceException to make the stack trace more friendly
			// Otherwise a generic unable to request service is thrown
			throw log.unableToConfigureDatastoreProvider( e );
		}
	}

	@Override
	public void start() {
		try {
			Hosts.HostAndPort hostAndPort = config.getHosts().getFirst();
			RedisURI.Builder builder = redis( hostAndPort.getHost(), hostAndPort.getPort() );
			builder.withSsl( config.isSsl() );
			builder.withDatabase( config.getDatabaseNumber() );

			if ( config.getPassword() != null ) {
				builder.withPassword( config.getPassword() );
			}

			builder.withTimeout( config.getTimeout(), TimeUnit.MILLISECONDS );

			log.connectingToRedis( config.getHosts().toString(), config.getTimeout() );
			connection = connect( builder.build() );

		}
		catch (RuntimeException e) {
			// return a ServiceException to be stack trace friendly
			throw log.unableToInitializeRedis( e );
		}
	}

	private RedisConnection<byte[], byte[]> connect(RedisURI redisURI) {
		try {
			return (RedisConnection<byte[], byte[]>) CONNECT_CODEC_URI.invoke( redisClient, CODEC, redisURI );
		}
		catch (InvocationTargetException ite) {
			if ( ite.getCause() instanceof RedisException ) {
				throw (RedisException) ite.getCause();
			}
			throw new RedisException( ite.getCause() );
		}
		catch (IllegalAccessException e) {
			throw new RedisException( e );
		}
	}

	@Override
	public void stop() {
		if ( connection != null ) {
			log.disconnectingFromRedis();
			connection.close();
			connection = null;
		}
	}

	@Override
	public void injectServices(ServiceRegistryImplementor serviceRegistry) {
		this.serviceRegistry = serviceRegistry;
	}

	@Override
	public boolean allowsTransactionEmulation() {
		return true;
	}

	public RedisConnection<byte[], byte[]> getConnection() {
		return connection;
	}
}