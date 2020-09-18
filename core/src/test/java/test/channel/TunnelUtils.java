package test.channel;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLHandshakeException;

import tlschannel.TlsChannel;

public class TunnelUtils {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final Map<String, Field> _FIELD_REFLECTION_CACHE = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public static <F, X> X uncheckedFieldAccess(Object declaringObject, String fieldName, Class<F> fieldType,
			BiFunction<Supplier<F>, Consumer<F>, X> reflectAccess) {
		Objects.requireNonNull(declaringObject);
		String cacheKey = Arrays.asList(declaringObject.getClass(), fieldName, fieldType).stream()
				.map(Objects::requireNonNull).map(v -> {
					if (v instanceof Class)
						return ((Class<?>) v).getName();
					return v.toString();
				}).collect(Collectors.joining("#"));
		var field = _FIELD_REFLECTION_CACHE.computeIfAbsent(cacheKey, nil -> {
			Stream<Field> fieldStream = streamHierarcy(declaringObject.getClass()).map(v -> {
				Stream<Field> stream = Stream.of();
				stream = Stream.concat(stream, Stream.of(v.getDeclaredFields()));
				stream = Stream.concat(stream, Stream.of(v.getFields()));
				stream = stream.distinct();
				stream = stream.filter(f -> fieldName.equals(f.getName()));
				stream = stream.filter(f -> fieldType.isAssignableFrom(f.getType()));
				return stream;
			}).flatMap(v -> v).distinct();
			var fields = fieldStream.limit(2).collect(Collectors.toList());
			if (fields.size() != 1)
				throw new NoSuchElementException(String.format(
						"field lookup failed. declaringType:%s fieldName:%s fieldType:%s",
						declaringObject == null ? null : declaringObject.getClass().getName(), fieldName, fieldType));
			var result = fields.get(0);
			result.setAccessible(true);
			return result;
		});
		return reflectAccess.apply(() -> {
			return (F) unchecked(() -> field.get(declaringObject));
		}, v -> {
			unchecked(() -> {
				field.set(declaringObject, v);
				return null;
			});
		});
	}

	private static final Map<String, Method> _METHOD_REFLECTION_CACHE = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public static <X> X uncheckedMethodInvoke(Object declaringObject, String methodName, Class<X> returnType,
			Object... arguments) {
		Objects.requireNonNull(declaringObject);
		var parameterCount = arguments == null ? 0 : arguments.length;
		String cacheKey = Arrays.asList(declaringObject.getClass(), methodName, returnType, parameterCount).stream()
				.map(Objects::requireNonNull).map(v -> {
					if (v instanceof Class)
						return ((Class<?>) v).getName();
					return v.toString();
				}).collect(Collectors.joining("#"));
		var method = _METHOD_REFLECTION_CACHE.computeIfAbsent(cacheKey, nil -> {
			Stream<Method> methodStream = streamHierarcy(declaringObject.getClass()).map(v -> {
				Stream<Method> stream = Stream.of();
				stream = Stream.concat(stream, Stream.of(v.getDeclaredMethods()));
				stream = Stream.concat(stream, Stream.of(v.getMethods()));
				stream = stream.distinct();
				stream = stream.filter(m -> !Modifier.isAbstract(m.getModifiers()));
				stream = stream.filter(m -> m.getParameterCount() == parameterCount);
				stream = stream.filter(m -> methodName.equals(m.getName()));
				stream = stream.filter(m -> returnType.isAssignableFrom(m.getReturnType()));
				return stream;
			}).flatMap(v -> v).distinct();
			var methods = methodStream.limit(2).collect(Collectors.toList());
			if (methods.size() != 1)
				throw new NoSuchElementException(String.format(
						"method lookup failed. declaringType:%s methodName:%s returnType:%s parameterCount:%s",
						declaringObject == null ? null : declaringObject.getClass().getName(), methodName, returnType,
						parameterCount));
			var result = methods.get(0);
			result.setAccessible(true);
			return result;
		});
		return (X) unchecked(() -> method.invoke(declaringObject, arguments));
	}

	private static Stream<Class<?>> streamHierarcy(Class<?> classType) {
		if (classType == null)
			return Stream.of(classType);
		Stream<Class<?>> stream = Stream.of(classType);
		stream = Stream.concat(stream, streamHierarcy(classType.getSuperclass()));
		return stream.filter(Objects::nonNull).distinct();
	}

	public static <X> X unchecked(Callable<X> callable) {
		try {
			return callable.call();
		} catch (Exception e) {
			throw (((Object) e) instanceof java.lang.RuntimeException) ? java.lang.RuntimeException.class.cast(e)
					: new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	public static <X, T extends Throwable> X tryThrowAs(Exception exception, Class<T> exceptionType) throws T {
		if (exceptionType == null)
			throw new RuntimeException();
		if (exceptionType.isAssignableFrom(exception.getClass()))
			throw (T) exception;
		throw new RuntimeException(exception);
	}

	public static boolean closeQuietly(Closeable... closeables) {
		if (closeables == null)
			return false;
		boolean result = false;
		for (var closeable : closeables) {
			if (closeable == null)
				continue;
			try {
				closeable.close();
				result = true;
			} catch (IOException e) {
				logger.trace("error during close", e);
			}
		}
		return result;
	}

	public static boolean closeAndLogOnError(String message, Throwable error, Closeable... closeables) {
		if (error == null)
			return false;
		TunnelUtils.closeQuietly(closeables);
		if (error instanceof AsynchronousCloseException)
			return true;
		if (error instanceof ClosedChannelException)
			return true;
		logger.error(message, error);
		return true;
	}

	public static boolean isCertificateUnknownError(Throwable error) {
		if (error instanceof SSLHandshakeException) {
			String msg = Optional.ofNullable(error.getMessage()).map(String::toLowerCase).orElse("");
			if (msg.contains("Received fatal alert: certificate_unknown".toLowerCase()))
				return true;
		}
		return false;
	}

	public static String formatSummary(String prepend, Map<String, Object> summaryData) {
		if (prepend == null)
			prepend = "";
		else if (!prepend.isBlank())
			prepend = prepend + " ";
		if (summaryData == null)
			return prepend + "";
		String result = summaryData.entrySet().stream().filter(ent -> ent.getKey() != null && ent.getValue() != null)
				.map(ent -> {
					var valueStr = ent.getValue().toString();
					if (valueStr.isBlank())
						return null;
					return String.format("%s:%s", ent.getKey(), valueStr);
				}).filter(Objects::nonNull).collect(Collectors.joining(" "));
		return prepend + result;
	}

	public static Map<String, Object> getSummary(AsynchronousTlsChannelExt asyncTlsChannel) {
		return getSummary(asyncTlsChannel == null ? null : asyncTlsChannel.getTlsChannel());
	}

	public static Map<String, Object> getSummary(TlsChannel tlsChannel) {
		Map<String, Object> logData = new LinkedHashMap<>();
		if (tlsChannel == null)
			return logData;
		if (tlsChannel instanceof ServerTlsChannelExt) {
			var sniServerName = ((ServerTlsChannelExt) tlsChannel).getSniServerName();
			logData.put("sniServerNameValue", getSNIServerNameValue(sniServerName));
		}
		Optional<SocketChannel> socketChannelOp = Optional.ofNullable(tlsChannel.getUnderlying()).map(v -> {
			if (v instanceof SocketChannel)
				return (SocketChannel) v;
			return null;
		});
		logData.put("remoteAddress", socketChannelOp.map(v -> {
			try {
				return v.getRemoteAddress();
			} catch (IOException e) {
				return null;
			}
		}).map(Object::toString).orElse(null));
		logData.put("localaddress", socketChannelOp.map(v -> {
			try {
				return v.getLocalAddress();
			} catch (IOException e) {
				return null;
			}
		}).map(Object::toString).orElse(null));
		return logData;
	}

	public static String getSNIServerNameValue(SNIServerName sniServerName) {
		if (sniServerName == null)
			return null;
		if (sniServerName instanceof SNIHostName)
			return ((SNIHostName) sniServerName).getAsciiName();
		String str = sniServerName.toString();
		String token = "value=";
		var index = str.lastIndexOf(token);
		if (index < 0)
			return null;
		str = str.substring(index + token.length());
		if (str.isEmpty())
			return null;
		return str;
	}
}
