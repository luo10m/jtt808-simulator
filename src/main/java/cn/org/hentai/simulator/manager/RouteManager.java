package cn.org.hentai.simulator.manager;

import cn.org.hentai.simulator.entity.*;
import cn.org.hentai.simulator.util.BeanUtils;
import cn.org.hentai.simulator.util.LBSUtils;
import cn.org.hentai.simulator.web.entity.Route;
import cn.org.hentai.simulator.web.entity.RoutePoint;
import cn.org.hentai.simulator.web.entity.StayPoint;
import cn.org.hentai.simulator.web.entity.TroubleSegment;
import cn.org.hentai.simulator.web.service.XRoutePointService;
import cn.org.hentai.simulator.web.service.XRouteService;
import cn.org.hentai.simulator.web.service.XStayPointService;
import cn.org.hentai.simulator.web.service.XTroubleSegmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by houcheng on 2018/11/23.
 * 线路缓存管理
 */
public final class RouteManager
{
    private static Logger logger = LoggerFactory.getLogger(RouteManager.class);

    // 在进行停留点比较时，取样的点的数量
    static final int SCOPE = 5;
    // 停留点到线路轨迹的最长距离
    static final int MAX_DISNTANCE_TO_STAYPOINT = 2500;

    static RouteManager instance = null;

    int sequence = 1;

    ConcurrentHashMap<Long, XRoute> routes = new ConcurrentHashMap<Long, XRoute>();
    private RouteManager()
    {
        // ...
    }

    public static synchronized RouteManager getInstance()
    {
        if (null == instance) instance = new RouteManager();
        return instance;
    }

    // 初始化，加载数据库中的线路以及其问题路段、停留点等信息
    // 此方法暂时只是从文件中去加载数据
    public void init()
    {
        logger.info("初始化线路开始...");
        if (routes.size() > 0) return;
        XRouteService routeService = null;
        try
        {
            // 安全事件分类初始化/缓存
            EventCache.init();

            routeService = BeanUtils.create(XRouteService.class);
            List<Route> routes = routeService.find();
            for (Route route : routes)
            {
                load(route);
            }
            logger.info("初始化线路结束...");
        }
        catch(Exception ex)
        {
            logger.error("初始化线路异常！", ex);
        }
        finally
        {
            try { BeanUtils.destroy(routeService); } catch(Exception e) {}
        }
    }

    // 将routeId指定的线路信息加载到缓存中来
    // 此方法留予Controller层面调用，当用户在页面上创建或修改了线路信息时，调用此方法将线路信息加载到缓存中来
    public void load(Long routeId)
    {
        Route route = null;
        XRouteService routeService = null;
        try
        {
            routeService = BeanUtils.create(XRouteService.class);
            route = routeService.getById(routeId);
            if (null == route) throw new RuntimeException("no such route: " + routeId);

            load(route);
        }
        catch(Exception ex)
        {
            logger.error("", ex);
        }
        finally
        {
            try { BeanUtils.destroy(routeService); } catch(Exception e) { }
        }
    }

    public void load(Route route)
    {
        XRoute xr = new XRoute();
        XRoutePointService pointService = null;
        XStayPointService stayPointService = null;
        XTroubleSegmentService segmentService = null;
        try
        {
            pointService = BeanUtils.create(XRoutePointService.class);
            stayPointService = BeanUtils.create(XStayPointService.class);
            segmentService = BeanUtils.create(XTroubleSegmentService.class);

            xr.setMinSpeed(route.getMinSpeed());
            xr.setMaxSpeed(route.getMaxSpeed());
            xr.setId(route.getId());
            xr.setFingerPrint(route.getFingerPrint());

            // 轨迹点
            List<Position> points = new LinkedList();
            for (RoutePoint rp : pointService.find(route.getId()))
            {
                Position p = new Position(rp.getLongitude(), rp.getLatitude());
                points.add(p);
            }
            xr.setPositionList(points);

            // 停留点
            LinkedList<XStayPoint> stayPoints = new LinkedList();
            for (StayPoint sp : stayPointService.find(route.getId()))
            {
                XStayPoint xsp = new XStayPoint(sp.getLongitude(), sp.getLatitude(), sp.getMinTime(), sp.getMaxTime(), sp.getRatio());
                stayPoints.add(xsp);
            }
            xr.setVehicleStayPointList(stayPoints);

            // 问题路段
            LinkedList<XTroubleSegment> segments = new LinkedList();
            for (TroubleSegment segment : segmentService.find(route.getId()))
            {
                XTroubleSegment xts = new XTroubleSegment(segment.getStartIndex(), segment.getEndIndex(), segment.getEventCode(), segment.getRatio());
                segments.add(xts);
            }
            xr.setTroubleSegmentList(segments);

            routes.put(route.getId(), xr);
        }
        catch(Exception ex)
        {
            logger.error("", ex);
        }
        finally
        {
            try { BeanUtils.destroy(pointService); } catch(Exception e) { }
            try { BeanUtils.destroy(stayPointService); } catch(Exception e) { }
            try { BeanUtils.destroy(segmentService); } catch(Exception e) { }
        }
    }

    // 删除id为routeId的线路
    public void remove(Long routeId)
    {
        routes.remove(routeId);
    }

    public DrivePlan generate(Long routeId, Date startTime)
    {
        LinkedList<Point> points = new LinkedList();
        XRoute route = routes.get(routeId);
        List<Position> positionList = route.getPositionList();

        // 行驶速度随机化、轨迹点随机化、确定每一个点的到达时间
        points = routeRandomize(positionList, route.getMinSpeed(), route.getMaxSpeed());

        // 产生安全事件
        generateEvents(points, route.getTroubleSegmentList());

        // 生成停留点，并且修正上报时间
        // 同时完善安全事件信息
        List<XEvent> eventList = generateStayPoints(startTime, points, route.getVehicleStayPointList());

        DrivePlan plan = new DrivePlan();
        plan.setRoutePoints(points);
        plan.setEvents(eventList);

        return plan;
    }

    // 创建安全事件
    private void generateEvents(LinkedList<Point> points, List<XTroubleSegment> troubleSegmentList)
    {
        if (troubleSegmentList.size() == 0) return;

        int i = -1;
        int eindex = 0;
        for (Point point : points)
        {
            i += 1;

            // 循环问题路段的轨迹点
            for (int k = eindex; k < troubleSegmentList.size(); k++)
            {
                XTroubleSegment segment = troubleSegmentList.get(k);
                if (segment == null) continue;

                boolean match = i >= segment.getStartIndex() && i <= segment.getEndIndex();
                if (!match) continue;

                // 如果轨迹点所在的索引处于某一问题路段内，则根据概率记录安全事件类型
                if (Math.random() * 100 < segment.getRatio())
                {
                    // 获取此问题路段的安全事件
                    point.setEvent(segment.getEvent());
                    // troubleSegmentList.set(k, null);
                    eindex += 1;
                }
            }
        }
    }

    // 创建停留点
    private List<XEvent> generateStayPoints(Date startTime, LinkedList<Point> points, List<XStayPoint> vehicleStayPointList)
    {
        int sindex = 0;
        for (int i = 0; i < points.size(); )
        {
            int positionSize = points.size();
            Point position = points.get(i);
            if (vehicleStayPointList.size() == 0) break;
            if (sindex >= vehicleStayPointList.size()) break;

            XStayPoint stayPoint = vehicleStayPointList.get(sindex);

            // 此处应该计算点到线段间的距离
            // TODO: 停留点的速度应该很低很低，延长发送时长到15秒吧
            int distance = LBSUtils.directDistance(position.getLongitude(), position.getLatitude(), stayPoint.getLongitude(), stayPoint.getLatitude());
            if (distance > MAX_DISNTANCE_TO_STAYPOINT)
            {
                // 如果当前点距离停留点大于150米，则不设置停留点
                // 重新将此停留点添加到列表中
                // vehicleStayPointList.addFirst(stayPoint);
                i += 1;
                continue;
            }

            // 距离停留点最近的轨迹点
            boolean isFound = true;
            for (int k = Math.max(0, i - 3), s = i + SCOPE; k < s && k < positionSize; k++)
            {
                Point comparePosition = points.get(k);
                // TODO: 此处应该计算点到线段的距离，可使用海伦公式计算三角形的面积，然后再通过（面积 = 底 * 高 / 2）反推高度
                // 因为当前的停留点，可能会因为车辆时速过快，而超过了MAX_DISTANCE_TO_STAYPOINT
                int compareDistance = LBSUtils.directDistance(comparePosition.getLongitude(), comparePosition.getLatitude(), stayPoint.getLongitude(), stayPoint.getLatitude());
                if (compareDistance < distance)
                {
                    isFound = false;
                    break;
                }
            }

            if (isFound == false)
            {
                // vehicleStayPointList.addFirst(stayPoint);
                i += 1;
                continue;
            }
            sindex += 1;

            // 由概率决定要不要停留
            double rand = Math.random() * 100;
            if (rand > stayPoint.getRatio())
            {
                i += 1;
                continue;
            }

            // 设置N个停留点
            // 从最低时长与最高时长中随机一个时长
            int appropriateTime = (int) Math.round(Math.random() * (stayPoint.getMaxTime() - stayPoint.getMinTime()) + stayPoint.getMinTime());

            // 统一在经度的第5位设置随机数
            Double newLng = stayPoint.getLongitude();
            Double newLat = stayPoint.getLatitude();

            // 循环停留时长
            for (int m = 0, e = appropriateTime * 60 * 1000; m < e; )
            {
                double r1 = (Math.random() * 20 - 10) * Math.pow(10, -5);
                double r2 = (Math.random() * 20 - 10) * Math.pow(10, -5);
                m += 15000;
                Point newPoint = new Point();
                newPoint.setLongitude(newLng + r1);
                newPoint.setLatitude(newLat + r2);
                newPoint.setEvent("stay");
                points.add(i, newPoint);
                i += 1;
            }
            // System.out.println(String.format("StayPoint @ [%.6f x %.6f]", newLng, newLat));
        }
        // System.out.println("***************************************************************************************************");

        List<XEvent> eventList = new LinkedList();
        long time = startTime.getTime();
        for (Point point : points)
        {
            boolean isStayPoint = "stay".equals(point.getEvent());
            time += 5000;
            if (isStayPoint)
            {
                point.setEvent(null);
                time += 10000;
            }
            if (point.getEvent() != null)
            {
                // 将安全事件大类转为小类，从大类里随机取一个作为确定的安全事件
                String eventTypeCode = point.getEvent();
                String eventCode = EventCache.get(eventTypeCode).getCode();
                point.setEvent(null);

                // 事件的开始、结束
                int time1 = 0, time2 = 0, duration = 0;
                if ("e-danger".equals(eventTypeCode))
                {
                    time1 = (int)(10 + Math.random() * 10);
                    time2 = (int)(2 + Math.random() * 5);
                    duration = (int)Math.max(time1, Math.random() * 60);
                }
                else
                {
                    duration = (int)(Math.random() * 15);
                }
                int seq = (sequence++) & 0xffff;
                XEvent e = new XEvent()
                        .setCode(eventCode)
                        .setReportTime(time)
                        .setType(1)
                        .setTime1(0)
                        .setTime2(0)
                        .setLongitude(point.getLongitude())
                        .setLatitude(point.getLatitude())
                        .setSpeed(point.getSpeed())
                        .setSequence(seq);
                eventList.add(e);

                e = new XEvent()
                        .setCode(eventCode)
                        .setReportTime(time + duration * 1000)
                        .setType(2)
                        .setTime1(time1)
                        .setTime2(time2)
                        .setSequence(seq);
                eventList.add(e);
            }
            point.setReportTime(time);
        }

        return eventList;
    }

    // 轨迹点随机化
    private LinkedList<Point> routeRandomize(List<Position> positionList, int minSpeed, int maxSpeed)
    {
        // 每SAMPLING_RATIO个轨迹点生成一个随机速度点，然后再在之间进行更小范围的随机化
        final int SAMPLING_RATIO = 60;

        // 生成时速随机区间，速度为：米/秒。
        int lastspeedCurve = 0;
        float[] speedCurve = new float[positionList.size() / SAMPLING_RATIO + 10];
        for (int i = 0; i < speedCurve.length; i++) speedCurve[i] = (float)(Math.random() * (maxSpeed - minSpeed) + minSpeed) * 1000 / 3600;

        LinkedList<Point> points = new LinkedList();
        for (int i = 0, l = positionList.size(); i < l; )
        {
            Position current = positionList.get(i);
            Position next = i < l - 1 ? positionList.get(i + 1) : null;

            // TODO: 速度应该从0开始，到0结束
            ////////////////////////////////////////////////////////////////////////////////////////////////////////
            // 计算在这个节点时它应该取的速度值
            float speed = speedCurve[i / SAMPLING_RATIO];
            float nextSpeed = speedCurve[i / SAMPLING_RATIO + 1];
            float diff = nextSpeed - speed;
            // p1 : [  0, sp ]
            // p2 : [ 60, ns ]
            // x = i % 60
            // y = kx
            float k = diff / SAMPLING_RATIO;
            float x = i % SAMPLING_RATIO;
            diff = (diff / SAMPLING_RATIO) * 2;
            // 在[+diff, -diff]之间取随机值
            // System.out.println(String.format("speed: %.2f, nextSpeed: %.2f", speed, nextSpeed));
            // System.out.println(String.format("i = %d, k = %.6f, x = %.0f", i, k, x));
            // System.out.println("*******************************");
            float y = k * x + (float)(diff - Math.random() * diff * 2);
            speed = y + speed;
            if (Float.isNaN(speed) || Float.isInfinite(speed)) speed = speedCurve[i / SAMPLING_RATIO];
            // else System.out.println(String.format("%.2f", speed));

            // 确定一下速度曲线

            // 以当前点为起点，以speed的速度，行驶5秒后，应该到哪里了。。

            // 下一个时间点，应该到达多远之后
            int distanceToNext = (int)(speed * 5);
            LinkedList<Position> partial = new LinkedList<Position>();
            partial.add(current);
            int d = 0;
            for (i = i + 1; i < l; i++, d++)
            {
                partial.add(positionList.get(i));
                int meters = LBSUtils.measure(partial);
                if (meters < distanceToNext) continue;

                // Log.debug(String.format("skiped: %d, at speed: %.1f", d, speed));

                // 确定了到达第i点才是应该到的地方了，接下来
                // 1. 从超过的点起，到下一个点之间，随机取一个点
                Position from = positionList.get(i - 1), to = positionList.get(i);
                Position pt = randomizeNextPosition(from, to);
                Point p = new Point();
                p.setLongitude(pt.getLongitude());
                p.setLatitude(pt.getLatitude());
                p.setSpeed((float)(speed * 3600 / 1000));
                points.add(p);
                break;
            }
        }
        return points;
    }

    // 随机化轨迹点，不完全按照即定的轨迹点来行进
    private Position randomizeNextPosition(Position p1, Position p2)
    {
        final int M = 1000000;

        // 计算斜率
        if (p1.getLongitude().doubleValue() == p2.getLongitude().doubleValue())
        {
            // Log.debug("因为k不存在，所以取了p2");
            return p2;
        }
        double k = (p1.getLatitude() * M - p2.getLatitude() * M) / (p1.getLongitude() * M - p2.getLongitude() * M);
        // 计算角度
        double a = Math.atan(k);
        // 计算距离
        double r = Math.sqrt(Math.pow((p1.getLongitude() * M - p2.getLongitude() * M), 2) + Math.pow((p1.getLatitude() * M - p2.getLatitude() * M), 2));
        // 取20%~80%的r长度，重新计算p1到p2的坐标
        // 原来是p1通过a角度经过r的长度到达p2，现在r缩减到20%~80%，重新计算落点
        // r = ((Math.random() * 6) + 2) * r / 10;
        // r在[+10%, -10%]之间随机取值
        // System.out.println(String.format("r = %.6f", r));
        r = r + (r / 5 - Math.random() * (r / 2));
        // System.out.println(String.format("r = %.6f", r));
        // 根据余弦公式计算x坐标
        double x = Math.cos(a) * r;
        // 通过勾股定律计算y坐标
        double y = Math.sqrt(r * r - x * x);
        // System.out.println(String.format("k = %.6f, a = %.6f", k, a));
        // System.out.println(String.format("x = %.6f, y = %.6f", x, y));

        // if (k < 0) y = 0 - y;
        if (p2.getLatitude() < p1.getLatitude() && y > 0) y = 0 - y;
        if (p2.getLongitude() < p1.getLongitude() && x > 0) x = 0 - x;

        // System.out.println(String.format("x = %.6f, y = %.6f", x, y));

        x = x / M + p1.getLongitude();
        y = y / M + p1.getLatitude();

        if (Double.isNaN(x) || Double.isNaN(y))
        {
            // Log.debug("因为NAN而使用了p2");
            return p2;
        }

        return new Position(x, y);
    }

    // 安全事件缓存
    private static class EventCache
    {
        private static List<Event> dangerEvents, tireEvents, illegalEvents, adasWarnings, vehicleHitches, deviceHitches, platformEvents;

        public static void setCache(String type, List<Event> events)
        {
            switch (type)
            {
                case "e-danger" : dangerEvents = events; break;
                case "e-tire" : tireEvents = events; break;
                case "e-illegal" : illegalEvents = events; break;
                case "e-adas" : adasWarnings = events; break;
                case "e-vhitch" : vehicleHitches = events; break;
                case "e-dhitch" : deviceHitches = events; break;
                case "e-platform" : platformEvents = events; break;
                default : throw new RuntimeException("unknown event type: " + type);
            }
        }

        public static void init()
        {
            try
            {
                String[] types = new String[] { "e-illegal", "e-danger", "e-tire", "e-adas", "e-vhitch", "e-dhitch", "e-platform" };
                for (String type : types)
                {
                    setCache(type, null);
                }
            }
            catch(Exception e)
            {
                logger.error("安全事件分类初始化/缓存异常！", e);
            }
        }

        public static Event get(String type)
        {
            List<Event> events = null;
            switch (type)
            {
                case "e-danger" : events = dangerEvents; break;
                case "e-tire" : events = tireEvents; break;
                case "e-illegal" : events = illegalEvents; break;
                case "e-adas" : events = adasWarnings; break;
                case "e-vhitch" : events = vehicleHitches; break;
                case "e-dhitch" : events = deviceHitches; break;
                case "e-platform" : events = platformEvents; break;
                default : throw new RuntimeException("unknown event type: " + type);
            }
            return events.get((int)(Math.random() * events.size()));
        }
    }
}